import { computed, inject, Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuthResponse, LoginRequest, RegisterRequest, Role } from '../models/auth.model';

/**
 * Token lives only in memory (a signal) -- never localStorage/sessionStorage.
 * A hard refresh logging the user out is the accepted tradeoff for v1 (see
 * the frontend build brief). AuthInterceptor reads `token()` on every
 * outgoing request; nothing else should touch storage for auth state.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  private readonly _token = signal<string | null>(null);
  private readonly _tenantSlug = signal<string | null>(null);
  private readonly _email = signal<string | null>(null);
  private readonly _role = signal<Role | null>(null);

  readonly token = this._token.asReadonly();
  readonly tenantSlug = this._tenantSlug.asReadonly();
  readonly email = this._email.asReadonly();
  readonly role = this._role.asReadonly();

  readonly isAuthenticated = computed(() => this._token() !== null);
  readonly isAdmin = computed(() => this._role() === 'ADMIN');

  login(request: LoginRequest): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>(`${environment.apiBaseUrl}/auth/login`, request)
      .pipe(tap((response) => this.setSession(response)));
  }

  register(request: RegisterRequest): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>(`${environment.apiBaseUrl}/auth/register`, request)
      .pipe(tap((response) => this.setSession(response)));
  }

  logout(): void {
    this._token.set(null);
    this._tenantSlug.set(null);
    this._email.set(null);
    this._role.set(null);
  }

  private setSession(response: AuthResponse): void {
    this._token.set(response.token);
    this._tenantSlug.set(response.tenantSlug);
    this._email.set(response.email);
    this._role.set(response.role);
  }
}
