-- V35__execution_cost_tracking.sql
--
-- Turns token counts into money, and gives a tenant a spend ceiling.
--
-- V19 started recording input/output/total tokens per execution, and V20/V21
-- added a per-execution maxTokens cap. Between them they answer "how big was
-- that run?" -- but nothing has ever answered "what did it cost?", and the
-- only ceiling in the system is per-execution (max_tokens) or concurrent
-- (execution_limit.max-concurrent-per-tenant). Neither bounds spend over time:
-- five concurrent runs, forever, is unbounded.
--
-- That gap mattered less when every execution began with a human clicking
-- something. V34 changed that -- a GitHub webhook now queues runs with nobody
-- watching, so a busy repository, a CI loop that pushes fifty commits, or one
-- misconfigured endpoint spends a real vendor credential unattended. This
-- migration is the ceiling that unattended trigger needs.

-- ============================================================
-- MODEL PRICING  (global reference data -- deliberately NOT tenant-scoped)
-- ============================================================
-- No RLS on this table, matching agent_definitions (V6): it is seeded
-- catalogue data describing the outside world, identical for every tenant,
-- and contains nothing belonging to anyone. A tenant reading another
-- tenant's row is not a concept here -- there are no tenant rows.
CREATE TABLE model_pricing (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Matches vendor_credentials.provider / LlmProvider (ANTHROPIC, OPENAI,
    -- GEMINI, LOCAL). Recorded for reporting and so a future "price every
    -- model this provider offers" query is possible; pricing itself is keyed
    -- by model_name, because that is what actually determines the rate.
    provider              VARCHAR(50)  NOT NULL,

    -- The exact string passed to the provider (AgentPromptRunner resolves it
    -- from the agent definition's preferred model, else the tenant's, else
    -- the server default). Stored verbatim, including any date suffix, so
    -- claude-sonnet-4-5-20250929 and claude-sonnet-4-6 price separately --
    -- they genuinely cost different amounts.
    model_name            VARCHAR(200) NOT NULL,

    -- Per MILLION tokens, which is how every vendor publishes and how every
    -- human cross-checks a bill. Storing per-token instead would mean six
    -- more decimal places of rounding error and a number nobody can eyeball
    -- against a price list. NUMERIC (never DOUBLE PRECISION): money, and
    -- binary floating point cannot represent 0.1 exactly.
    input_usd_per_mtok    NUMERIC(12, 6) NOT NULL CHECK (input_usd_per_mtok  >= 0),
    output_usd_per_mtok   NUMERIC(12, 6) NOT NULL CHECK (output_usd_per_mtok >= 0),

    -- Prices change, and a run must be costed at the price in effect WHEN IT
    -- RAN -- not at whatever the price happens to be when someone opens a
    -- report next quarter. That is why this table is append-only-by-
    -- convention (a new price is a new row with a later effective_from,
    -- never an UPDATE of the old one) and why agent_executions.cost_usd
    -- below is denormalized at completion time rather than computed on read.
    -- Same reasoning an invoice line stores its own price.
    effective_from        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    notes                 VARCHAR(500),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- One price per model per effective date. Re-running this migration's
    -- seed, or an admin adding today's price twice, is a constraint
    -- violation rather than a silent duplicate that makes "the current
    -- price" ambiguous.
    UNIQUE (model_name, effective_from)
);

CREATE INDEX idx_model_pricing_lookup ON model_pricing(model_name, effective_from DESC);

-- Seeded from Anthropic's published price list as of 2026-08-19.
--
-- Anthropic models only, and that is not an oversight: these are the rates
-- this project's maintainer can cite. OPENAI/GEMINI/LOCAL are deliberately
-- absent rather than guessed -- see ExecutionCostCalculator for what happens
-- to a run on an unpriced model (it is recorded as UNPRICED, never as free).
-- An operator adds their own rows for other providers; nothing in the schema
-- is Anthropic-specific.
INSERT INTO model_pricing (provider, model_name, input_usd_per_mtok, output_usd_per_mtok, notes) VALUES
    ('ANTHROPIC', 'claude-fable-5',              10.000000, 50.000000, 'Anthropic list price, 2026-08-19'),
    ('ANTHROPIC', 'claude-mythos-5',             10.000000, 50.000000, 'Anthropic list price, 2026-08-19'),
    ('ANTHROPIC', 'claude-opus-5',                5.000000, 25.000000, 'Anthropic list price, 2026-08-19'),
    ('ANTHROPIC', 'claude-opus-4-8',              5.000000, 25.000000, 'Anthropic list price, 2026-08-19'),
    ('ANTHROPIC', 'claude-opus-4-7',              5.000000, 25.000000, 'Anthropic list price, 2026-08-19'),
    ('ANTHROPIC', 'claude-opus-4-6',              5.000000, 25.000000, 'Anthropic list price, 2026-08-19'),
    ('ANTHROPIC', 'claude-opus-4-5-20251101',     5.000000, 25.000000, 'Anthropic list price, 2026-08-19'),
    -- Sonnet 5 is on introductory pricing ($2/$10 vs $3/$15) through
    -- 2026-08-31. When that lapses, the correct action is INSERT a new row
    -- with effective_from = 2026-09-01 and the standard rate -- NOT an
    -- UPDATE of this one, which would retroactively re-price every run
    -- already costed at the introductory rate.
    ('ANTHROPIC', 'claude-sonnet-5',              2.000000, 10.000000, 'Anthropic introductory price through 2026-08-31'),
    ('ANTHROPIC', 'claude-sonnet-4-6',            3.000000, 15.000000, 'Anthropic list price, 2026-08-19'),
    -- The server default (app.llm.anthropic-model-name in application.yml),
    -- so an out-of-the-box run is priced rather than UNPRICED.
    ('ANTHROPIC', 'claude-sonnet-4-5-20250929',   3.000000, 15.000000, 'Anthropic list price, 2026-08-19'),
    ('ANTHROPIC', 'claude-haiku-4-5-20251001',    1.000000,  5.000000, 'Anthropic list price, 2026-08-19');

-- ============================================================
-- PER-EXECUTION COST
-- ============================================================
-- Which model actually ran. Necessary and previously missing: llm_provider
-- was recorded (V1) but the model was not, and price is per-MODEL, not per-
-- provider -- claude-haiku-4-5 and claude-opus-5 are both ANTHROPIC and
-- differ 25x on output. You cannot price a run whose model you did not
-- record, so this column is a prerequisite for cost_usd rather than a nice
-- extra. Stamped by AgentJobWorker before the run starts, so it survives a
-- crash mid-run.
ALTER TABLE agent_executions ADD COLUMN model_name VARCHAR(200);

-- What this run cost, in USD, at the price in effect when it completed.
-- NULL is meaningful and is NOT zero -- it means "not priced", either
-- because the run recorded no token usage (see AgentExecution.totalTokens)
-- or because no model_pricing row covers its model. Every consumer must
-- treat NULL as unknown; billing an unpriced run as $0.00 is exactly the
-- silent under-count this table exists to prevent.
ALTER TABLE agent_executions ADD COLUMN cost_usd NUMERIC(14, 6);

-- Month-to-date spend for one tenant is the hot query (it runs on the
-- enqueue path, before every single execution), so it gets its own index.
CREATE INDEX idx_agent_executions_tenant_cost
    ON agent_executions(tenant_id, completed_at)
    WHERE cost_usd IS NOT NULL;

-- ============================================================
-- PER-TENANT MONTHLY BUDGET
-- ============================================================
-- NULL = no ceiling, which stays the default for every existing tenant:
-- this migration must not retroactively cut off a tenant who never asked for
-- a budget. An explicit 0 is a real value meaning "spend nothing", and is
-- how an admin freezes a tenant without deleting anything.
--
-- Deliberately a soft ceiling checked at enqueue time, not a hard one: an
-- execution already RUNNING is never killed mid-run for crossing it. Killing
-- a run wastes everything already spent on it and can leave a repository
-- half-modified -- the cheapest safe moment to say no is before starting.
-- Consequence, stated plainly rather than hidden: actual spend can overshoot
-- the budget by at most the cost of the runs in flight when it was crossed.
ALTER TABLE tenants ADD COLUMN monthly_budget_usd NUMERIC(12, 2)
    CHECK (monthly_budget_usd IS NULL OR monthly_budget_usd >= 0);
