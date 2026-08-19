import { DatePipe } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AgentService } from '../../core/services/agent.service';
import { AuthService } from '../../core/services/auth.service';
import { TenantSettingsService } from '../../core/services/tenant-settings.service';
import { AgentSpendBreakdown, TenantSpendSummary } from '../../core/models/agent.model';
import { TenantSettings } from '../../core/models/credential.model';
import { extractErrorMessage } from '../../shared/http/error-message';

/**
 * Warn once the tenant has used this much of its budget. Below it the meter is
 * simply informational; at or above it the run that tips them over is close
 * enough to be worth acting on before it happens.
 */
const WARN_AT_PERCENT = 80;

type BudgetState = 'none' | 'ok' | 'warn' | 'over';

@Component({
  selector: 'app-spend',
  imports: [FormsModule, DatePipe],
  templateUrl: './spend.html',
  styleUrl: './spend.css',
})
export class Spend implements OnInit {
  private readonly agentService = inject(AgentService);
  private readonly tenantSettingsService = inject(TenantSettingsService);
  readonly authService = inject(AuthService);

  readonly summary = signal<TenantSpendSummary | null>(null);
  readonly loading = signal(true);
  readonly loadError = signal<string | null>(null);

  /** Admin-only budget editor -- kept separate from the summary so a failed save never corrupts what's displayed. */
  readonly settings = signal<TenantSettings | null>(null);
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);
  readonly saveSuccess = signal<string | null>(null);
  budgetInput: number | null = null;

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.agentService.getSpend().subscribe({
      next: (summary) => {
        this.summary.set(summary);
        this.loading.set(false);
      },
      error: (err) => {
        this.loadError.set(extractErrorMessage(err, 'Failed to load spend.'));
        this.loading.set(false);
      },
    });
    // Only an ADMIN can change the ceiling, so only an ADMIN needs the
    // settings payload -- everyone else reads the budget off the summary.
    if (this.authService.isAdmin()) {
      this.tenantSettingsService.getSettings().subscribe({
        next: (settings) => {
          this.settings.set(settings);
          this.budgetInput = settings.monthlyBudgetUsd;
        },
        // Deliberately silent: the budget editor is secondary, and failing to
        // load it must not bury the spend figures behind an error banner.
        error: () => undefined,
      });
    }
  }

  /**
   * Drives the meter's color AND its written state -- red and amber are close
   * to indistinguishable under deuteranopia (verified: ΔE 2.8), so the label
   * this feeds is what actually carries the meaning. The color only reinforces
   * it.
   */
  readonly budgetState = computed<BudgetState>(() => {
    const s = this.summary();
    if (!s || s.budgetUsd === null || s.percentUsed === null) {
      return 'none';
    }
    if (s.percentUsed >= 100) {
      return 'over';
    }
    return s.percentUsed >= WARN_AT_PERCENT ? 'warn' : 'ok';
  });

  readonly budgetLabel = computed<string>(() => {
    switch (this.budgetState()) {
      case 'over':
        return 'Over budget — new runs are blocked';
      case 'warn':
        return 'Approaching the budget';
      case 'ok':
        return 'Within budget';
      default:
        return 'No budget set';
    }
  });

  /** Clamped for the bar's width only -- the printed percentage stays truthful past 100%. */
  readonly meterWidth = computed<number>(() => {
    const pct = this.summary()?.percentUsed ?? 0;
    return Math.max(0, Math.min(100, pct));
  });

  /**
   * The largest priced agent cost, used as the bar scale. Null-cost agents are
   * excluded rather than counted as zero -- they have no bar at all, and the
   * table says "unpriced" instead of drawing a misleading empty one.
   */
  readonly maxAgentCost = computed<number>(() => {
    const rows = this.summary()?.byAgent ?? [];
    return rows.reduce((max, row) => (row.costUsd !== null && row.costUsd > max ? row.costUsd : max), 0);
  });

  barWidth(row: AgentSpendBreakdown): number {
    const max = this.maxAgentCost();
    // No bar for an unknown cost, and none for a genuine zero either: a
    // self-hosted run really did cost nothing, and drawing a sliver for it
    // would imply spend that did not happen.
    if (row.costUsd === null || row.costUsd <= 0 || max <= 0) {
      return 0;
    }
    // Floor the rest at 1% so a real-but-tiny cost stays a visible mark
    // instead of rounding away to nothing and reading as "no spend".
    return Math.max(1, (row.costUsd / max) * 100);
  }

  /**
   * Formats money for display. Sub-cent costs are real (a small local or Haiku
   * run genuinely costs fractions of a cent), so they get more precision
   * instead of being rounded to $0.00 -- which would read as free.
   */
  formatUsd(value: number): string {
    if (value > 0 && value < 0.01) {
      return `$${value.toFixed(4)}`;
    }
    return `$${value.toFixed(2)}`;
  }

  saveBudget(): void {
    const settings = this.settings();
    if (!settings) {
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    this.saveSuccess.set(null);
    // A full replace, not a patch -- every other field is sent back unchanged
    // so saving a budget can never silently clear the provider or model.
    this.tenantSettingsService
      .updateSettings(
        settings.preferredLlmProvider,
        settings.preferredModelName,
        settings.maxTokensPerExecution,
        this.budgetInput === null || (this.budgetInput as unknown as string) === '' ? null : Number(this.budgetInput),
      )
      .subscribe({
        next: (updated) => {
          this.settings.set(updated);
          this.budgetInput = updated.monthlyBudgetUsd;
          this.saving.set(false);
          this.saveSuccess.set(
            updated.monthlyBudgetUsd === null
              ? 'Budget cleared — this tenant is now unlimited.'
              : `Budget set to ${this.formatUsd(updated.monthlyBudgetUsd)} per month.`,
          );
          // Re-read the summary so remaining/percent reflect the new ceiling
          // immediately rather than after a manual refresh.
          this.agentService.getSpend().subscribe({ next: (s) => this.summary.set(s) });
        },
        error: (err) => {
          this.saveError.set(extractErrorMessage(err, 'Failed to save the budget.'));
          this.saving.set(false);
        },
      });
  }
}
