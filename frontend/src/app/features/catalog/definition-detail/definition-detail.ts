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

  ngOnInit(): void {
    const slug = this.route.snapshot.paramMap.get('slug')!;
    this.agentService.getDefinition(slug).subscribe({
      next: (detail) => {
        this.definition.set(detail);
        this.loading.set(false);
      },
      error: () => {
        this.notFound.set(true);
        this.loading.set(false);
      },
    });
  }
}
