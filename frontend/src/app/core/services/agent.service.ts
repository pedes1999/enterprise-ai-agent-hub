import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  AgentDefinitionDetail,
  AgentDefinitionSummary,
  AgentExecutionAccepted,
  AgentExecutionStatusResponse,
  AgentTokenUsageStats,
  ExecutionUsage,
  PagedModel,
  ToolExecutionRecord,
  TriggerAgentExecutionRequest,
} from '../models/agent.model';

@Injectable({ providedIn: 'root' })
export class AgentService {
  private readonly http = inject(HttpClient);

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

  getTokenUsageStats(agentSlug: string): Observable<AgentTokenUsageStats> {
    return this.http.get<AgentTokenUsageStats>(`${environment.apiBaseUrl}/agents/executions/token-usage-stats`, {
      params: new HttpParams().set('agentSlug', agentSlug),
    });
  }
}
