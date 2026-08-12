import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AgentService } from '../../core/services/agent.service';
import { AgentDefinitionSummary } from '../../core/models/agent.model';

@Component({
  selector: 'app-catalog',
  imports: [RouterLink],
  templateUrl: './catalog.html',
  styleUrl: './catalog.css',
})
export class Catalog implements OnInit {
  private readonly agentService = inject(AgentService);

  readonly definitions = signal<AgentDefinitionSummary[]>([]);
  readonly loading = signal(true);
  readonly loadError = signal<string | null>(null);

  ngOnInit(): void {
    this.agentService.listDefinitions().subscribe({
      next: (list) => {
        this.definitions.set(list);
        this.loading.set(false);
      },
      error: (err) => {
        this.loadError.set(err.error?.message ?? 'Failed to load the agent catalog.');
        this.loading.set(false);
      },
    });
  }
}
