import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { Spend } from './spend';
import { environment } from '../../../environments/environment';
import { TenantSpendSummary } from '../../core/models/agent.model';
import { AuthService } from '../../core/services/auth.service';

describe('Spend', () => {
  let httpMock: HttpTestingController;

  const summary = (overrides: Partial<TenantSpendSummary> = {}): TenantSpendSummary => ({
    periodStart: '2026-08-01T00:00:00Z',
    spendUsd: 4.5,
    budgetUsd: 10,
    remainingUsd: 5.5,
    percentUsed: 45,
    unpricedExecutions: 0,
    byAgent: [],
    ...overrides,
  });

  /** Non-admin by default -- the budget editor is admin-only and issues a second request. */
  async function setup(isAdmin = false): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [Spend],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: { isAdmin: () => isAdmin } },
      ],
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
  }

  afterEach(() => httpMock.verify());

  it('loads the spend summary on init', async () => {
    await setup();
    const fixture = TestBed.createComponent(Spend);
    fixture.detectChanges();

    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/spend`).flush(summary());

    expect(fixture.componentInstance.summary()?.spendUsd).toBe(4.5);
    expect(fixture.componentInstance.loading()).toBe(false);
  });

  it('reports a budget state of ok well under the ceiling', async () => {
    await setup();
    const fixture = TestBed.createComponent(Spend);
    fixture.detectChanges();
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/spend`).flush(summary({ percentUsed: 45 }));

    expect(fixture.componentInstance.budgetState()).toBe('ok');
    expect(fixture.componentInstance.budgetLabel()).toContain('Within budget');
  });

  it('warns before the ceiling is actually reached', async () => {
    await setup();
    const fixture = TestBed.createComponent(Spend);
    fixture.detectChanges();
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/spend`).flush(summary({ percentUsed: 85 }));

    expect(fixture.componentInstance.budgetState()).toBe('warn');
  });

  it('treats a tenant at or past the ceiling as over budget', async () => {
    await setup();
    const fixture = TestBed.createComponent(Spend);
    fixture.detectChanges();
    httpMock
      .expectOne(`${environment.apiBaseUrl}/agents/executions/spend`)
      .flush(summary({ spendUsd: 13.5, percentUsed: 135, remainingUsd: 0 }));

    expect(fixture.componentInstance.budgetState()).toBe('over');
    expect(fixture.componentInstance.budgetLabel()).toContain('blocked');
    // The bar is clamped so it cannot overflow its track...
    expect(fixture.componentInstance.meterWidth()).toBe(100);
    // ...but the figure the reader sees stays truthful.
    expect(fixture.componentInstance.summary()?.percentUsed).toBe(135);
  });

  it('reports no budget state when the tenant has no ceiling', async () => {
    await setup();
    const fixture = TestBed.createComponent(Spend);
    fixture.detectChanges();
    httpMock
      .expectOne(`${environment.apiBaseUrl}/agents/executions/spend`)
      .flush(summary({ budgetUsd: null, remainingUsd: null, percentUsed: null }));

    expect(fixture.componentInstance.budgetState()).toBe('none');
  });

  it('scales bars against the largest priced agent, ignoring unpriced ones', async () => {
    await setup();
    const fixture = TestBed.createComponent(Spend);
    fixture.detectChanges();
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/spend`).flush(
      summary({
        byAgent: [
          { agentSlug: 'planner', executionCount: 2, costUsd: 8, totalTokens: 100 },
          { agentSlug: 'general-assistant', executionCount: 1, costUsd: 2, totalTokens: 50 },
          { agentSlug: 'test-fixer', executionCount: 5, costUsd: null, totalTokens: 900 },
        ],
      }),
    );
    const c = fixture.componentInstance;

    expect(c.maxAgentCost()).toBe(8);
    expect(c.barWidth({ agentSlug: 'planner', executionCount: 2, costUsd: 8, totalTokens: 100 })).toBe(100);
    expect(c.barWidth({ agentSlug: 'general-assistant', executionCount: 1, costUsd: 2, totalTokens: 50 })).toBe(25);
    // An unpriced agent gets no bar -- a zero-width one would read as "no
    // spend", which is precisely the wrong conclusion.
    expect(c.barWidth({ agentSlug: 'test-fixer', executionCount: 5, costUsd: null, totalTokens: 900 })).toBe(0);
  });

  it('draws no bar for a genuinely free run, which is not the same as a tiny one', async () => {
    await setup();
    const fixture = TestBed.createComponent(Spend);
    fixture.detectChanges();
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/spend`).flush(
      summary({
        byAgent: [
          { agentSlug: 'planner', executionCount: 1, costUsd: 10, totalTokens: 10 },
          { agentSlug: 'general-assistant', executionCount: 1, costUsd: 0, totalTokens: 5400 },
        ],
      }),
    );

    // A self-hosted run really did cost nothing -- a sliver would imply spend
    // that did not happen. The $0.00 in the cost column carries it instead.
    expect(
      fixture.componentInstance.barWidth({ agentSlug: 'general-assistant', executionCount: 1, costUsd: 0, totalTokens: 5400 }),
    ).toBe(0);
  });

  it('keeps a real but tiny cost visible instead of rounding it away', async () => {
    await setup();
    const fixture = TestBed.createComponent(Spend);
    fixture.detectChanges();
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/spend`).flush(
      summary({
        byAgent: [
          { agentSlug: 'planner', executionCount: 1, costUsd: 100, totalTokens: 10 },
          { agentSlug: 'tiny', executionCount: 1, costUsd: 0.002, totalTokens: 10 },
        ],
      }),
    );

    expect(fixture.componentInstance.barWidth({ agentSlug: 'tiny', executionCount: 1, costUsd: 0.002, totalTokens: 10 }))
      .toBe(1);
  });

  it('shows sub-cent costs at more precision rather than as $0.00', async () => {
    await setup();
    const fixture = TestBed.createComponent(Spend);
    fixture.detectChanges();
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/spend`).flush(summary());
    const c = fixture.componentInstance;

    // $0.00 would read as free, which is exactly what a fractional-cent run is not.
    expect(c.formatUsd(0.002)).toBe('$0.0020');
    expect(c.formatUsd(4.5)).toBe('$4.50');
    // A genuine zero still reads as zero -- self-hosted runs really are free.
    expect(c.formatUsd(0)).toBe('$0.00');
  });

  it('does not request tenant settings for a non-admin', async () => {
    await setup(false);
    const fixture = TestBed.createComponent(Spend);
    fixture.detectChanges();
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/spend`).flush(summary());

    httpMock.expectNone(`${environment.apiBaseUrl}/tenant-settings`);
  });

  it('sends every setting back when an admin saves a budget, so nothing else is cleared', async () => {
    await setup(true);
    const fixture = TestBed.createComponent(Spend);
    fixture.detectChanges();
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/spend`).flush(summary());
    httpMock.expectOne(`${environment.apiBaseUrl}/tenant-settings`).flush({
      preferredLlmProvider: 'LOCAL',
      preferredModelName: 'qwen2.5-coder:7b',
      maxTokensPerExecution: 2000,
      effectiveMaxTokensPerExecution: 2000,
      monthlyBudgetUsd: 10,
      availableProviders: [],
    });

    fixture.componentInstance.budgetInput = 25;
    fixture.componentInstance.saveBudget();

    const put = httpMock.expectOne(
      (r) => r.url === `${environment.apiBaseUrl}/tenant-settings` && r.method === 'PUT',
    );
    // updateSettings is a full replace -- omitting these would silently wipe
    // the tenant's provider and model just to change a number.
    expect(put.request.body).toEqual({
      preferredLlmProvider: 'LOCAL',
      preferredModelName: 'qwen2.5-coder:7b',
      maxTokensPerExecution: 2000,
      monthlyBudgetUsd: 25,
    });
    put.flush({
      preferredLlmProvider: 'LOCAL',
      preferredModelName: 'qwen2.5-coder:7b',
      maxTokensPerExecution: 2000,
      effectiveMaxTokensPerExecution: 2000,
      monthlyBudgetUsd: 25,
      availableProviders: [],
    });
    // Re-reads the summary so remaining/percent reflect the new ceiling at once.
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/spend`).flush(summary({ budgetUsd: 25 }));

    expect(fixture.componentInstance.saveSuccess()).toContain('$25.00');
  });

  it('sends null to clear a budget rather than zero, which means "spend nothing"', async () => {
    await setup(true);
    const fixture = TestBed.createComponent(Spend);
    fixture.detectChanges();
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/spend`).flush(summary());
    httpMock.expectOne(`${environment.apiBaseUrl}/tenant-settings`).flush({
      preferredLlmProvider: null,
      preferredModelName: null,
      maxTokensPerExecution: null,
      effectiveMaxTokensPerExecution: 500000,
      monthlyBudgetUsd: 10,
      availableProviders: [],
    });

    fixture.componentInstance.budgetInput = null;
    fixture.componentInstance.saveBudget();

    const put = httpMock.expectOne((r) => r.method === 'PUT');
    expect(put.request.body.monthlyBudgetUsd).toBeNull();
    put.flush({
      preferredLlmProvider: null,
      preferredModelName: null,
      maxTokensPerExecution: null,
      effectiveMaxTokensPerExecution: 500000,
      monthlyBudgetUsd: null,
      availableProviders: [],
    });
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/spend`).flush(summary({ budgetUsd: null }));

    expect(fixture.componentInstance.saveSuccess()).toContain('unlimited');
  });
});
