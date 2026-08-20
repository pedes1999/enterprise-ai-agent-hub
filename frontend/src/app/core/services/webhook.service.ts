import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  CreateWebhookEndpointRequest,
  WebhookEndpointCreatedResponse,
  WebhookEndpointSummary,
} from '../models/webhook.model';

@Injectable({ providedIn: 'root' })
export class WebhookService {
  private readonly http = inject(HttpClient);

  list(): Observable<WebhookEndpointSummary[]> {
    return this.http.get<WebhookEndpointSummary[]>(`${environment.apiBaseUrl}/webhook-endpoints`);
  }

  create(request: CreateWebhookEndpointRequest): Observable<WebhookEndpointCreatedResponse> {
    return this.http.post<WebhookEndpointCreatedResponse>(`${environment.apiBaseUrl}/webhook-endpoints`, request);
  }

  deactivate(id: string): Observable<void> {
    return this.http.delete<void>(`${environment.apiBaseUrl}/webhook-endpoints/${id}`);
  }
}
