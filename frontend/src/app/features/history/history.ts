import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AgentService } from '../../core/services/agent.service';
import { AgentExecutionStatusResponse, ExecutionStatus, PagedModel } from '../../core/models/agent.model';
import { LocalDateTimePipe } from '../../shared/pipes/local-date-time.pipe';

const PAGE_SIZE = 20;

@Component({
  selector: 'app-history',
  imports: [FormsModule, RouterLink, LocalDateTimePipe],
  templateUrl: './history.html',
  styleUrl: './history.css',
})
export class History implements OnInit {
  private readonly agentService = inject(AgentService);

  readonly page = signal<PagedModel<AgentExecutionStatusResponse> | null>(null);
  readonly loading = signal(true);
  readonly loadError = signal<string | null>(null);
  readonly currentPage = signal(0);
  statusFilter: ExecutionStatus | '' = '';

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.agentService.listExecutions(this.currentPage(), PAGE_SIZE, this.statusFilter || undefined).subscribe({
      next: (result) => {
        this.page.set(result);
        this.loading.set(false);
      },
      error: (err) => {
        this.loadError.set(err.error?.message ?? 'Failed to load execution history.');
        this.loading.set(false);
      },
    });
  }

  onFilterChange(): void {
    this.currentPage.set(0);
    this.load();
  }

  nextPage(): void {
    const p = this.page();
    if (p && this.currentPage() + 1 < p.page.totalPages) {
      this.currentPage.update((n) => n + 1);
      this.load();
    }
  }

  previousPage(): void {
    if (this.currentPage() > 0) {
      this.currentPage.update((n) => n - 1);
      this.load();
    }
  }
}
