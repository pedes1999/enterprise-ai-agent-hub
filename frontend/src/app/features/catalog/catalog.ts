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

  ngOnInit(): void {
    this.agentService.listDefinitions().subscribe({
      next: (list) => {
        this.definitions.set(list);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }
}
