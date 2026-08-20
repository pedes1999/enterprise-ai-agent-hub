import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { WebhookService } from '../../core/services/webhook.service';
import { AgentService } from '../../core/services/agent.service';
import { UserService } from '../../core/services/user.service';
import { WebhookEndpointCreatedResponse, WebhookEndpointSummary } from '../../core/models/webhook.model';
import { AgentDefinitionSummary } from '../../core/models/agent.model';
import { UserSummary } from '../../core/models/user.model';
import { LocalDateTimePipe } from '../../shared/pipes/local-date-time.pipe';
import { extractErrorMessage } from '../../shared/http/error-message';
import { RowMessage } from '../../shared/models/row-message';

@Component({
  selector: 'app-webhooks',
  imports: [FormsModule, LocalDateTimePipe],
  templateUrl: './webhooks.html',
  styleUrl: './webhooks.css',
})
export class Webhooks implements OnInit {
  private readonly webhookService = inject(WebhookService);
  private readonly agentService = inject(AgentService);
  private readonly userService = inject(UserService);

  readonly endpoints = signal<WebhookEndpointSummary[]>([]);
  readonly agents = signal<AgentDefinitionSummary[]>([]);
  readonly users = signal<UserSummary[]>([]);
  readonly loading = signal(true);
  readonly loadError = signal<string | null>(null);

  newAgentSlug = '';
  newLabel = '';
  newRunAsUserId = '';

  readonly creating = signal(false);
  readonly createError = signal<string | null>(null);
  /** Shown exactly once, right after creation -- the backend never returns the secret again. */
  readonly justCreated = signal<WebhookEndpointCreatedResponse | null>(null);
  readonly copied = signal<'secret' | 'url' | null>(null);

  readonly pendingId = signal<string | null>(null);
  readonly rowMessages: Record<string, RowMessage> = {};

  ngOnInit(): void {
    this.refresh();
    this.agentService.listDefinitions().subscribe({
      next: (list) => this.agents.set(list),
      error: () => {
        /* the create form still works with a manually-typed slug if this fails */
      },
    });
    this.userService.list().subscribe({
      next: (list) => this.users.set(list),
      error: () => {
        /* runAsUserId falls back to "myself" (omitted) if this fails */
      },
    });
  }

  private refresh(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.webhookService.list().subscribe({
      next: (list) => {
        this.endpoints.set(list);
        this.loading.set(false);
      },
      error: (err) => {
        this.loadError.set(extractErrorMessage(err, 'Failed to load webhook endpoints.'));
        this.loading.set(false);
      },
    });
  }

  createEndpoint(): void {
    if (!this.newAgentSlug || !this.newLabel) {
      return;
    }
    this.creating.set(true);
    this.createError.set(null);
    this.justCreated.set(null);

    this.webhookService
      .create({
        agentSlug: this.newAgentSlug,
        label: this.newLabel,
        runAsUserId: this.newRunAsUserId || null,
      })
      .subscribe({
        next: (created) => {
          this.creating.set(false);
          this.justCreated.set(created);
          this.newAgentSlug = '';
          this.newLabel = '';
          this.newRunAsUserId = '';
          this.refresh();
        },
        error: (err) => {
          this.creating.set(false);
          this.createError.set(extractErrorMessage(err, 'Failed to create webhook endpoint.'));
        },
      });
  }

  dismissCreated(): void {
    this.justCreated.set(null);
    this.copied.set(null);
  }

  copy(text: string, which: 'secret' | 'url'): void {
    navigator.clipboard.writeText(text).then(() => {
      this.copied.set(which);
      setTimeout(() => this.copied.set(null), 2000);
    });
  }

  deactivate(endpoint: WebhookEndpointSummary): void {
    this.pendingId.set(endpoint.id);
    delete this.rowMessages[endpoint.id];
    this.webhookService.deactivate(endpoint.id).subscribe({
      next: () => {
        this.pendingId.set(null);
        this.refresh();
      },
      error: (err) => {
        this.pendingId.set(null);
        this.rowMessages[endpoint.id] = { kind: 'error', text: extractErrorMessage(err, 'Failed to remove endpoint.') };
      },
    });
  }
}
