import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { TenantSettings } from '../models/credential.model';

@Injectable({ providedIn: 'root' })
export class TenantSettingsService {
  private readonly http = inject(HttpClient);

  getSettings(): Observable<TenantSettings> {
    return this.http.get<TenantSettings>(`${environment.apiBaseUrl}/tenant-settings`);
  }

  updatePreferredLlmProvider(preferredLlmProvider: string | null): Observable<TenantSettings> {
    return this.http.put<TenantSettings>(`${environment.apiBaseUrl}/tenant-settings`, { preferredLlmProvider });
  }
}
