import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { environment } from '../../../environments/environment';
import { AuthResponse } from '../models/auth.model';

const STORAGE_KEY = 'auth.session';

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  const authResponse: AuthResponse = {
    token: 'jwt-token-abc',
    expiresInSeconds: 3600,
    tenantId: 'tenant-1',
    tenantSlug: 'acme',
    userId: 'user-1',
    email: 'dev@acme.com',
    role: 'DEVELOPER',
    mustChangePassword: false,
  };

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    vi.useRealTimers();
    localStorage.clear();
  });

  it('starts unauthenticated with no token', () => {
    expect(service.isAuthenticated()).toBe(false);
    expect(service.token()).toBeNull();
  });

  it('login() posts to /auth/login with the exact request shape and stores the session', () => {
    service.login({ tenantSlug: 'acme', email: 'dev@acme.com', password: 'hunter2' }).subscribe();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/auth/login`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ tenantSlug: 'acme', email: 'dev@acme.com', password: 'hunter2' });
    req.flush(authResponse);

    expect(service.isAuthenticated()).toBe(true);
    expect(service.token()).toBe('jwt-token-abc');
    expect(service.tenantSlug()).toBe('acme');
    expect(service.role()).toBe('DEVELOPER');
  });

  it('register() posts to /auth/register with the exact request shape and stores the session', () => {
    service
      .register({ tenantName: 'Acme', tenantSlug: 'acme', email: 'admin@acme.com', password: 'hunter2' })
      .subscribe();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/auth/register`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      tenantName: 'Acme',
      tenantSlug: 'acme',
      email: 'admin@acme.com',
      password: 'hunter2',
    });
    req.flush({ ...authResponse, role: 'ADMIN', email: 'admin@acme.com' });

    expect(service.isAuthenticated()).toBe(true);
    expect(service.isAdmin()).toBe(true);
  });

  it('logout() clears the session and localStorage', () => {
    service.login({ tenantSlug: 'acme', email: 'dev@acme.com', password: 'hunter2' }).subscribe();
    httpMock.expectOne(`${environment.apiBaseUrl}/auth/login`).flush(authResponse);
    expect(service.isAuthenticated()).toBe(true);

    service.logout();

    expect(service.isAuthenticated()).toBe(false);
    expect(service.token()).toBeNull();
    expect(service.tenantSlug()).toBeNull();
    expect(service.role()).toBeNull();
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('isAdmin() is false for a DEVELOPER session', () => {
    service.login({ tenantSlug: 'acme', email: 'dev@acme.com', password: 'hunter2' }).subscribe();
    httpMock.expectOne(`${environment.apiBaseUrl}/auth/login`).flush(authResponse);

    expect(service.isAdmin()).toBe(false);
  });

  it('a failed login leaves the service unauthenticated', () => {
    let errored = false;
    service.login({ tenantSlug: 'acme', email: 'dev@acme.com', password: 'wrong' }).subscribe({
      error: () => (errored = true),
    });

    httpMock
      .expectOne(`${environment.apiBaseUrl}/auth/login`)
      .flush({ message: 'Invalid credentials' }, { status: 401, statusText: 'Unauthorized' });

    expect(errored).toBe(true);
    expect(service.isAuthenticated()).toBe(false);
  });

  it('login() persists the session to localStorage with an expiresAt derived from expiresInSeconds', () => {
    const before = Date.now();
    service.login({ tenantSlug: 'acme', email: 'dev@acme.com', password: 'hunter2' }).subscribe();
    httpMock.expectOne(`${environment.apiBaseUrl}/auth/login`).flush(authResponse);

    const stored = JSON.parse(localStorage.getItem(STORAGE_KEY)!);
    expect(stored.token).toBe('jwt-token-abc');
    expect(stored.tenantSlug).toBe('acme');
    expect(stored.role).toBe('DEVELOPER');
    expect(stored.expiresAt).toBeGreaterThanOrEqual(before + authResponse.expiresInSeconds * 1000);
  });

  it('a new AuthService instance restores the session from localStorage (survives a refresh)', () => {
    service.login({ tenantSlug: 'acme', email: 'dev@acme.com', password: 'hunter2' }).subscribe();
    httpMock.expectOne(`${environment.apiBaseUrl}/auth/login`).flush(authResponse);

    // Simulate a page refresh: a brand-new service instance reads whatever is in localStorage.
    const restored = TestBed.runInInjectionContext(() => new AuthService());
    expect(restored).not.toBe(service);
    expect(restored.isAuthenticated()).toBe(true);
    expect(restored.token()).toBe('jwt-token-abc');
    expect(restored.tenantSlug()).toBe('acme');
    expect(restored.role()).toBe('DEVELOPER');
  });

  it('discards an already-expired stored session instead of restoring it', () => {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        token: 'stale-token',
        tenantSlug: 'acme',
        email: 'dev@acme.com',
        role: 'DEVELOPER',
        expiresAt: Date.now() - 1000,
      }),
    );

    const restored = TestBed.runInInjectionContext(() => new AuthService());

    expect(restored.isAuthenticated()).toBe(false);
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('discards a corrupt stored session instead of throwing', () => {
    localStorage.setItem(STORAGE_KEY, 'not valid json{');

    const restored = TestBed.runInInjectionContext(() => new AuthService());

    expect(restored.isAuthenticated()).toBe(false);
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('login() propagates mustChangePassword=true for an admin-invited user still on their temp password', () => {
    service.login({ tenantSlug: 'acme', email: 'dev@acme.com', password: 'Tmp9!xyz' }).subscribe();

    httpMock.expectOne(`${environment.apiBaseUrl}/auth/login`).flush({ ...authResponse, mustChangePassword: true });

    expect(service.mustChangePassword()).toBe(true);
  });

  it('changePassword() posts to /auth/change-password and replaces the session with the fresh token', () => {
    service.login({ tenantSlug: 'acme', email: 'dev@acme.com', password: 'Tmp9!xyz' }).subscribe();
    httpMock.expectOne(`${environment.apiBaseUrl}/auth/login`).flush({ ...authResponse, mustChangePassword: true });
    expect(service.mustChangePassword()).toBe(true);

    service.changePassword({ currentPassword: 'Tmp9!xyz', newPassword: 'N3w!password' }).subscribe();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/auth/change-password`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ currentPassword: 'Tmp9!xyz', newPassword: 'N3w!password' });
    req.flush({ ...authResponse, token: 'new-jwt-token', mustChangePassword: false });

    expect(service.mustChangePassword()).toBe(false);
    expect(service.token()).toBe('new-jwt-token');
  });

  it('automatically logs out once the token reaches its own expiry while the tab stays open', () => {
    vi.useFakeTimers();
    service.login({ tenantSlug: 'acme', email: 'dev@acme.com', password: 'hunter2' }).subscribe();
    httpMock.expectOne(`${environment.apiBaseUrl}/auth/login`).flush(authResponse);
    expect(service.isAuthenticated()).toBe(true);

    vi.advanceTimersByTime(authResponse.expiresInSeconds * 1000 - 1);
    expect(service.isAuthenticated()).toBe(true);

    vi.advanceTimersByTime(1);
    expect(service.isAuthenticated()).toBe(false);
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
  });
});
