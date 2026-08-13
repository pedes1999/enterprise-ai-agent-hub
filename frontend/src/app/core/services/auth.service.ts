import { computed, inject, Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuthResponse, ChangePasswordRequest, LoginRequest, RegisterRequest, Role } from '../models/auth.model';

const STORAGE_KEY = 'auth.session';

interface StoredSession {
  token: string;
  tenantSlug: string;
  email: string;
  role: Role;
  mustChangePassword: boolean;
  expiresAt: number;
}

/**
 * The session (token + claims) is persisted to localStorage so a page
 * refresh doesn't log the user out -- but only for the JWT's own lifetime
 * (expiresInSeconds from the backend, currently 1h). There is no refresh
 * token: once expiresAt passes, logout() fires automatically, whether the
 * tab was open the whole time (via the scheduled timer) or reloaded after
 * expiry (via restoreSession() finding a stale entry). Storing the JWT in
 * localStorage (readable by any injected script) is a real tradeoff against
 * XSS versus the in-memory-only alternative; kept deliberately short-lived
 * and single-token (no long-lived refresh credential) to bound that risk.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private expiryTimer?: ReturnType<typeof setTimeout>;

  private readonly _token = signal<string | null>(null);
  private readonly _tenantSlug = signal<string | null>(null);
  private readonly _email = signal<string | null>(null);
  private readonly _role = signal<Role | null>(null);
  private readonly _mustChangePassword = signal(false);

  readonly token = this._token.asReadonly();
  readonly tenantSlug = this._tenantSlug.asReadonly();
  readonly email = this._email.asReadonly();
  readonly role = this._role.asReadonly();
  readonly mustChangePassword = this._mustChangePassword.asReadonly();

  readonly isAuthenticated = computed(() => this._token() !== null);
  readonly isAdmin = computed(() => this._role() === 'ADMIN');
  /** ADMIN and DEVELOPER can each bring their own vendor credential (see VendorCredentialController) -- READONLY cannot. */
  readonly canManageOwnCredentials = computed(() => this._role() === 'ADMIN' || this._role() === 'DEVELOPER');

  constructor() {
    this.restoreSession();
  }

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

  /**
   * The one request PasswordChangeRequiredFilter still lets through while
   * mustChangePassword is true (see the backend's javadoc on it) -- the
   * response carries a freshly issued token with the flag cleared, so
   * setSession() here is what actually unblocks the rest of the app
   * without forcing a second login.
   */
  changePassword(request: ChangePasswordRequest): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>(`${environment.apiBaseUrl}/auth/change-password`, request)
      .pipe(tap((response) => this.setSession(response)));
  }

  logout(): void {
    if (this.expiryTimer !== undefined) {
      clearTimeout(this.expiryTimer);
      this.expiryTimer = undefined;
    }
    this._token.set(null);
    this._tenantSlug.set(null);
    this._email.set(null);
    this._role.set(null);
    this._mustChangePassword.set(false);
    localStorage.removeItem(STORAGE_KEY);
  }

  private restoreSession(): void {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return;
    }
    let stored: StoredSession;
    try {
      stored = JSON.parse(raw);
    } catch {
      localStorage.removeItem(STORAGE_KEY);
      return;
    }
    const remainingMs = stored.expiresAt - Date.now();
    if (remainingMs <= 0) {
      localStorage.removeItem(STORAGE_KEY);
      return;
    }
    this._token.set(stored.token);
    this._tenantSlug.set(stored.tenantSlug);
    this._email.set(stored.email);
    this._role.set(stored.role);
    this._mustChangePassword.set(stored.mustChangePassword ?? false);
    this.scheduleExpiry(remainingMs);
  }

  private setSession(response: AuthResponse): void {
    this._token.set(response.token);
    this._tenantSlug.set(response.tenantSlug);
    this._email.set(response.email);
    this._role.set(response.role);
    this._mustChangePassword.set(response.mustChangePassword);

    const expiresInMs = response.expiresInSeconds * 1000;
    const stored: StoredSession = {
      token: response.token,
      tenantSlug: response.tenantSlug,
      email: response.email,
      role: response.role,
      mustChangePassword: response.mustChangePassword,
      expiresAt: Date.now() + expiresInMs,
    };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(stored));
    this.scheduleExpiry(expiresInMs);
  }

  private scheduleExpiry(ms: number): void {
    if (this.expiryTimer !== undefined) {
      clearTimeout(this.expiryTimer);
    }
    this.expiryTimer = setTimeout(() => this.logout(), ms);
  }
}
