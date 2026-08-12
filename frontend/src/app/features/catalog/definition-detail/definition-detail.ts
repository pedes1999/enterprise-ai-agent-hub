import { Component, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AgentService } from '../../../core/services/agent.service';
import { AgentDefinitionDetail } from '../../../core/models/agent.model';

@Component({
  selector: 'app-definition-detail',
  imports: [RouterLink],
  templateUrl: './definition-detail.html',
  styleUrl: './definition-detail.css',
})
export class DefinitionDetail implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly agentService = inject(AgentService);

  readonly definition = signal<AgentDefinitionDetail | null>(null);
  readonly loading = signal(true);
  readonly notFound = signal(false);
  readonly loadError = signal<string | null>(null);

  ngOnInit(): void {
    const slug = this.route.snapshot.paramMap.get('slug')!;
    this.agentService.getDefinition(slug).subscribe({
      next: (detail) => {
        this.definition.set(detail);
        this.loading.set(false);
      },
      error: (err) => {
        if (err.status === 404) {
          this.notFound.set(true);
        } else {
          this.loadError.set(err.error?.message ?? 'Failed to load this agent.');
        }
        this.loading.set(false);
      },
    });
  }
}
