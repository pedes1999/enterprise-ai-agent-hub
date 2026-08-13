import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  CreateToolCredentialRequest,
  CreateVendorCredentialRequest,
  CredentialTestResult,
  ModelOption,
  TeamVendorCredentialSummary,
  ToolCredentialSummary,
  VendorCredentialSummary,
} from '../models/credential.model';

@Injectable({ providedIn: 'root' })
export class CredentialService {
  private readonly http = inject(HttpClient);

  listVendorCredentials(): Observable<VendorCredentialSummary[]> {
    return this.http.get<VendorCredentialSummary[]>(`${environment.apiBaseUrl}/vendor-credentials`);
  }

  putVendorCredential(request: CreateVendorCredentialRequest): Observable<VendorCredentialSummary> {
    return this.http.put<VendorCredentialSummary>(`${environment.apiBaseUrl}/vendor-credentials`, request);
  }

  deleteVendorCredential(provider: string): Observable<void> {
    return this.http.delete<void>(`${environment.apiBaseUrl}/vendor-credentials/${provider}`);
  }

  testVendorCredential(provider: string): Observable<CredentialTestResult> {
    return this.http.post<CredentialTestResult>(`${environment.apiBaseUrl}/vendor-credentials/test`, { provider });
  }

  listModels(provider: string): Observable<ModelOption[]> {
    return this.http.get<ModelOption[]>(`${environment.apiBaseUrl}/vendor-credentials/${provider}/models`);
  }

  /** ADMIN-only read-only view across every teammate's vendor credentials -- see VendorCredentialController's /team endpoint. */
  listTeamCredentials(): Observable<TeamVendorCredentialSummary[]> {
    return this.http.get<TeamVendorCredentialSummary[]>(`${environment.apiBaseUrl}/vendor-credentials/team`);
  }

  /** Blind deactivate -- flips active=false without ever reading the underlying token. */
  deactivateTeamCredential(userId: string, provider: string): Observable<void> {
    return this.http.post<void>(`${environment.apiBaseUrl}/vendor-credentials/team/${userId}/${provider}/deactivate`, {});
  }

  listToolCredentials(): Observable<ToolCredentialSummary[]> {
    return this.http.get<ToolCredentialSummary[]>(`${environment.apiBaseUrl}/tool-credentials`);
  }

  putToolCredential(request: CreateToolCredentialRequest): Observable<ToolCredentialSummary> {
    return this.http.put<ToolCredentialSummary>(`${environment.apiBaseUrl}/tool-credentials`, request);
  }

  deleteToolCredential(credentialKind: string): Observable<void> {
    return this.http.delete<void>(`${environment.apiBaseUrl}/tool-credentials/${credentialKind}`);
  }

  testToolCredential(credentialKind: string): Observable<CredentialTestResult> {
    return this.http.post<CredentialTestResult>(`${environment.apiBaseUrl}/tool-credentials/test`, {
      credentialKind,
    });
  }
}
