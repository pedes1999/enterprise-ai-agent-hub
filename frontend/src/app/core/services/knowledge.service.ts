import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  AgentKnowledgeSourceBindingSummary,
  CreateKnowledgeSourceRequest,
  IngestDocumentResponse,
  KnowledgeSourceSummary,
  RetrievalQueryRequest,
  RetrievedChunkResult,
} from '../models/knowledge.model';

@Injectable({ providedIn: 'root' })
export class KnowledgeService {
  private readonly http = inject(HttpClient);

  list(): Observable<KnowledgeSourceSummary[]> {
    return this.http.get<KnowledgeSourceSummary[]>(`${environment.apiBaseUrl}/knowledge-sources`);
  }

  create(request: CreateKnowledgeSourceRequest): Observable<KnowledgeSourceSummary> {
    return this.http.post<KnowledgeSourceSummary>(`${environment.apiBaseUrl}/knowledge-sources`, request);
  }

  /**
   * FormData, not a JSON body -- never set a Content-Type header manually
   * here: the browser derives the multipart boundary from the FormData
   * object itself when HttpClient sends it, and authInterceptor only ever
   * touches Authorization (see its javadoc), so nothing else on the request
   * path interferes with that.
   */
  uploadDocument(knowledgeSourceId: string, file: File): Observable<IngestDocumentResponse> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<IngestDocumentResponse>(
      `${environment.apiBaseUrl}/knowledge-sources/${knowledgeSourceId}/documents`,
      formData,
    );
  }

  query(knowledgeSourceId: string, request: RetrievalQueryRequest): Observable<RetrievedChunkResult[]> {
    return this.http.post<RetrievedChunkResult[]>(
      `${environment.apiBaseUrl}/knowledge-sources/${knowledgeSourceId}/query`,
      request,
    );
  }

  /** Null (not an error) when this agent has no knowledge source attached -- the backend returns 204 with no body, which HttpClient surfaces as a null response here. */
  getBindingForAgent(agentSlug: string): Observable<AgentKnowledgeSourceBindingSummary | null> {
    return this.http.get<AgentKnowledgeSourceBindingSummary | null>(
      `${environment.apiBaseUrl}/knowledge-sources/agent-bindings/${agentSlug}`,
    );
  }

  attachToAgent(knowledgeSourceId: string, agentSlug: string): Observable<void> {
    return this.http.put<void>(
      `${environment.apiBaseUrl}/knowledge-sources/${knowledgeSourceId}/agent-bindings/${agentSlug}`,
      {},
    );
  }

  detachFromAgent(knowledgeSourceId: string, agentSlug: string): Observable<void> {
    return this.http.delete<void>(
      `${environment.apiBaseUrl}/knowledge-sources/${knowledgeSourceId}/agent-bindings/${agentSlug}`,
    );
  }
}
