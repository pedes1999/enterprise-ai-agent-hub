export interface AgentDefinitionSummary {
  slug: string;
  name: string;
  description: string;
  toolNames: string[];
}

export interface AgentDefinitionDetail {
  slug: string;
  name: string;
  description: string;
  systemPrompt: string;
  toolNames: string[];
  inputSourceType: string | null;
  requiredInputs: string[];
  /** Null means this definition uses the tenant's/server's default model. */
  preferredModelName: string | null;
}

export interface TriggerAgentExecutionRequest {
  prompt: string | null;
  agentSlug: string | null;
  repositoryUrl: string | null;
  repositoryBranch: string | null;
  inputParameters: Record<string, string> | null;
  /** Null means "use this tenant's default token budget". Must be positive when given. */
  maxTokens: number | null;
}

export interface AgentExecutionAccepted {
  executionId: string;
  status: ExecutionStatus;
}

export type ExecutionStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

export interface AgentExecutionStatusResponse {
  id: string;
  status: ExecutionStatus;
  llmProvider: string;
  agentSlug: string;
  prompt: string | null;
  repositoryUrl: string | null;
  repositoryBranch: string | null;
  inputParameters: Record<string, string> | null;
  reply: string | null;
  toolWasUsed: boolean | null;
  errorMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  /** Optional -- absent on responses recorded before token tracking existed, or when the provider reported no usage data. Null and "not present" both mean "unknown", never "zero tokens spent". */
  inputTokens?: number | null;
  outputTokens?: number | null;
  totalTokens?: number | null;
  /** Null means this run used the tenant's (or server's) default budget instead of its own override. */
  maxTokensOverride?: number | null;
  /** Non-null only for a child execution the delegate_to_agent tool queued on behalf of another execution -- null for everything triggered directly. */
  parentExecutionId?: string | null;
  /**
   * Non-null once a cancel was requested against this execution while it was
   * RUNNING -- while status is still RUNNING, that's the "asked to stop,
   * still stopping" state (cancellation is cooperative, not instant). Always
   * null for a QUEUED cancel, which goes straight to CANCELLED instead.
   */
  cancellationRequestedAt?: string | null;
}

export interface ToolExecutionRecord {
  toolName: string;
  durationMs: number;
  outcome: 'SUCCESS' | 'FAILURE';
  errorMessage: string | null;
  createdAt: string;
}

export interface ExecutionUsage {
  active: number;
  limit: number;
}

/** sampleCount is how many past executions of this agent recorded usage -- 0 means minTokens/avgTokens/maxTokens are all null (no data yet), not zero. */
export interface AgentTokenUsageStats {
  agentSlug: string;
  sampleCount: number;
  minTokens: number | null;
  avgTokens: number | null;
  maxTokens: number | null;
}

/**
 * One agent's share of the tenant's spend this period.
 *
 * costUsd is null when every run of this agent in the window was unpriced --
 * NOT zero. Rendering it as $0.00 would undo the backend's whole point (an
 * unknown cost is not a free one) and would sort a possibly-expensive agent
 * to the bottom of the list.
 */
export interface AgentSpendBreakdown {
  agentSlug: string;
  executionCount: number;
  costUsd: number | null;
  totalTokens: number | null;
}

/**
 * Backs GET /agents/executions/spend.
 *
 * The nullable fields carry meaning and must not be coalesced in the view:
 *  - budgetUsd null  = no ceiling configured (unlimited), NOT a budget of 0.
 *  - remainingUsd / percentUsed are null exactly when budgetUsd is -- there is
 *    nothing to be a fraction of.
 *  - unpricedExecutions > 0 means spendUsd is a LOWER BOUND, not a total, and
 *    the UI is expected to say so rather than present a partial figure as
 *    complete.
 */
export interface TenantSpendSummary {
  periodStart: string;
  spendUsd: number;
  budgetUsd: number | null;
  remainingUsd: number | null;
  percentUsed: number | null;
  unpricedExecutions: number;
  byAgent: AgentSpendBreakdown[];
}

/** Mirrors Spring's PagedModel<T> JSON shape -- {content, page: {size, number, totalElements, totalPages}}. */
export interface PagedModel<T> {
  content: T[];
  page: {
    size: number;
    number: number;
    totalElements: number;
    totalPages: number;
  };
}
