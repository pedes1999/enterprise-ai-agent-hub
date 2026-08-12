import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  CreateToolCredentialRequest,
  CreateVendorCredentialRequest,
  CredentialTestResult,
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
