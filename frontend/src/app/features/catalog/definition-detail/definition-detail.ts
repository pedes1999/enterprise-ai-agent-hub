import { Component, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { AgentService } from '../../../core/services/agent.service';
import { KnowledgeService } from '../../../core/services/knowledge.service';
import { AuthService } from '../../../core/services/auth.service';
import { AgentDefinitionDetail } from '../../../core/models/agent.model';
import { AgentKnowledgeSourceBindingSummary, KnowledgeSourceSummary } from '../../../core/models/knowledge.model';

@Component({
  selector: 'app-definition-detail',
  imports: [RouterLink, FormsModule],
  templateUrl: './definition-detail.html',
  styleUrl: './definition-detail.css',
})
export class DefinitionDetail implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly agentService = inject(AgentService);
  private readonly knowledgeService = inject(KnowledgeService);
  private readonly authService = inject(AuthService);

  readonly isAdmin = this.authService.isAdmin;

  readonly definition = signal<AgentDefinitionDetail | null>(null);
  readonly loading = signal(true);
  readonly notFound = signal(false);
  readonly loadError = signal<string | null>(null);

  /** ADMIN-only: which knowledge source (if any) is attached to this agent -- see KnowledgeSourceController's binding endpoints. */
  readonly bindingLoading = signal(true);
  readonly binding = signal<AgentKnowledgeSourceBindingSummary | null>(null);
  readonly availableSources = signal<KnowledgeSourceSummary[]>([]);
  readonly selectedSourceId = signal('');
  readonly attaching = signal(false);
  readonly detaching = signal(false);
  readonly bindingActionError = signal<string | null>(null);

  private slug = '';

  ngOnInit(): void {
    this.slug = this.route.snapshot.paramMap.get('slug')!;
    this.agentService.getDefinition(this.slug).subscribe({
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

    if (this.isAdmin()) {
      this.loadBinding();
      this.knowledgeService.list().subscribe((list) => this.availableSources.set(list));
    }
  }

  private loadBinding(): void {
    this.bindingLoading.set(true);
    this.knowledgeService.getBindingForAgent(this.slug).subscribe({
      next: (binding) => {
        this.bindingLoading.set(false);
        this.binding.set(binding);
      },
      error: () => {
        // A knowledge source binding is a secondary feature of this page -- a
        // failure to load it shouldn't block viewing the agent's own
        // configuration above, so this fails silently into "none attached"
        // rather than showing a second error banner.
        this.bindingLoading.set(false);
        this.binding.set(null);
      },
    });
  }

  attachSource(): void {
    const sourceId = this.selectedSourceId();
    if (!sourceId) {
      return;
    }
    this.attaching.set(true);
    this.bindingActionError.set(null);
    this.knowledgeService.attachToAgent(sourceId, this.slug).subscribe({
      next: () => {
        this.attaching.set(false);
        this.loadBinding();
      },
      error: (err) => {
        this.attaching.set(false);
        this.bindingActionError.set(err.error?.message ?? 'Failed to attach knowledge source.');
      },
    });
  }

  detachSource(): void {
    this.detaching.set(true);
    this.bindingActionError.set(null);
    this.knowledgeService.detachFromAgent(this.binding()!.knowledgeSourceId, this.slug).subscribe({
      next: () => {
        this.detaching.set(false);
        this.binding.set(null);
      },
      error: (err) => {
        this.detaching.set(false);
        this.bindingActionError.set(err.error?.message ?? 'Failed to detach knowledge source.');
      },
    });
  }
}
