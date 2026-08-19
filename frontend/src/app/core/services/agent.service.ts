import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuthService } from './auth.service';
import {
  AgentDefinitionDetail,
  AgentDefinitionSummary,
  AgentExecutionAccepted,
  AgentExecutionStatusResponse,
  AgentTokenUsageStats,
  TenantSpendSummary,
  ExecutionUsage,
  PagedModel,
  ToolExecutionRecord,
  TriggerAgentExecutionRequest,
} from '../models/agent.model';

/** One decoded Server-Sent Event from the execution stream -- see AgentService.streamExecution(). */
export interface ExecutionStreamEvent {
  event: 'status' | 'tool';
  data: unknown;
}

@Injectable({ providedIn: 'root' })
export class AgentService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  listDefinitions(): Observable<AgentDefinitionSummary[]> {
    return this.http.get<AgentDefinitionSummary[]>(`${environment.apiBaseUrl}/agents/definitions`);
  }

  getDefinition(slug: string): Observable<AgentDefinitionDetail> {
    return this.http.get<AgentDefinitionDetail>(`${environment.apiBaseUrl}/agents/definitions/${slug}`);
  }

  execute(request: TriggerAgentExecutionRequest): Observable<AgentExecutionAccepted> {
    return this.http.post<AgentExecutionAccepted>(`${environment.apiBaseUrl}/agents/execute`, request);
  }

  getExecution(id: string): Observable<AgentExecutionStatusResponse> {
    return this.http.get<AgentExecutionStatusResponse>(`${environment.apiBaseUrl}/agents/executions/${id}`);
  }

  /** Cooperative, not instant -- see AgentExecutionStatusResponse.cancellationRequestedAt's doc. */
  cancel(id: string): Observable<void> {
    return this.http.post<void>(`${environment.apiBaseUrl}/agents/executions/${id}/cancel`, null);
  }

  /**
   * Live execution updates over Server-Sent Events, as an async iterable.
   *
   * fetch + ReadableStream rather than the browser's own EventSource: this
   * API authenticates with `Authorization: Bearer <jwt>` and EventSource
   * cannot set request headers at all. The usual workaround is putting the
   * token in a query string, which would leak a live credential into
   * browser history, server access logs, and any proxy in between -- not
   * worth it to save the ~30 lines of framing below. HttpClient can't do
   * this either: it buffers the whole response body, which never arrives
   * for a stream that stays open.
   *
   * The caller aborts by passing a signal (the component does this on
   * destroy) -- without that the connection, and the server-side poll task
   * behind it, outlive the page.
   */
  async *streamExecution(id: string, signal: AbortSignal): AsyncGenerator<ExecutionStreamEvent> {
    const response = await fetch(`${environment.apiBaseUrl}/agents/executions/${id}/stream`, {
      // application/json alongside the stream type on purpose: a failed
      // request (404, 403) answers with the API's ordinary JSON error body,
      // and an Accept of text/event-stream alone leaves the server no
      // acceptable representation for it -- turning a clean 404 into a 500.
      headers: {
        Authorization: `Bearer ${this.auth.token() ?? ''}`,
        Accept: 'text/event-stream, application/json',
      },
      signal,
    });
    if (!response.ok || !response.body) {
      throw new Error(`Execution stream failed with HTTP ${response.status}`);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) {
          return;
        }
        buffer += decoder.decode(value, { stream: true });

        // SSE frames are separated by a blank line. A chunk can split a
        // frame anywhere (or carry several at once), so only whole frames
        // are consumed here and the remainder stays buffered for the next
        // read -- parsing per-chunk instead would corrupt any event
        // unlucky enough to straddle a boundary.
        let separator = buffer.indexOf('\n\n');
        while (separator !== -1) {
          const frame = buffer.slice(0, separator);
          buffer = buffer.slice(separator + 2);
          const parsed = this.parseEventFrame(frame);
          if (parsed) {
            yield parsed;
          }
          separator = buffer.indexOf('\n\n');
        }
      }
    } finally {
      // Releasing the lock lets the abort actually tear the connection
      // down; without it the reader keeps the body pinned open.
      reader.releaseLock();
    }
  }

  /** Spring writes `event:<name>` and `data:<json>` lines; anything else in a frame (comments, retry hints) is ignored. */
  private parseEventFrame(frame: string): ExecutionStreamEvent | null {
    let event: string | null = null;
    const dataLines: string[] = [];
    for (const line of frame.split('\n')) {
      if (line.startsWith('event:')) {
        event = line.slice('event:'.length).trim();
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice('data:'.length).trim());
      }
    }
    if ((event !== 'status' && event !== 'tool') || dataLines.length === 0) {
      return null;
    }
    try {
      return { event, data: JSON.parse(dataLines.join('\n')) };
    } catch {
      return null;
    }
  }

  listExecutions(page: number, size: number, status?: string): Observable<PagedModel<AgentExecutionStatusResponse>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (status) {
      params = params.set('status', status);
    }
    return this.http.get<PagedModel<AgentExecutionStatusResponse>>(`${environment.apiBaseUrl}/agents/executions`, {
      params,
    });
  }

  getToolExecutions(executionId: string): Observable<ToolExecutionRecord[]> {
    return this.http.get<ToolExecutionRecord[]>(
      `${environment.apiBaseUrl}/agents/executions/${executionId}/tool-executions`,
    );
  }

  /** Every execution the delegate_to_agent tool queued on behalf of this one -- empty, not an error, if it never delegated anything. */
  getChildren(executionId: string): Observable<AgentExecutionStatusResponse[]> {
    return this.http.get<AgentExecutionStatusResponse[]>(
      `${environment.apiBaseUrl}/agents/executions/${executionId}/children`,
    );
  }

  getUsage(): Observable<ExecutionUsage> {
    return this.http.get<ExecutionUsage>(`${environment.apiBaseUrl}/agents/executions/usage`);
  }

  /** Month-to-date spend against the tenant's budget -- see TenantSpendSummary on why its nulls matter. */
  getSpend(): Observable<TenantSpendSummary> {
    return this.http.get<TenantSpendSummary>(`${environment.apiBaseUrl}/agents/executions/spend`);
  }

  getTokenUsageStats(agentSlug: string): Observable<AgentTokenUsageStats> {
    return this.http.get<AgentTokenUsageStats>(`${environment.apiBaseUrl}/agents/executions/token-usage-stats`, {
      params: new HttpParams().set('agentSlug', agentSlug),
    });
  }
}
