import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { environment } from '../../../environments/environment';
import { AuthResponse } from '../models/auth.model';

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
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
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

  it('logout() clears the session', () => {
    service.login({ tenantSlug: 'acme', email: 'dev@acme.com', password: 'hunter2' }).subscribe();
    httpMock.expectOne(`${environment.apiBaseUrl}/auth/login`).flush(authResponse);
    expect(service.isAuthenticated()).toBe(true);

    service.logout();

    expect(service.isAuthenticated()).toBe(false);
    expect(service.token()).toBeNull();
    expect(service.tenantSlug()).toBeNull();
    expect(service.role()).toBeNull();
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
});
