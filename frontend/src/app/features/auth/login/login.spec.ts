import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { Login } from './login';
import { environment } from '../../../../environments/environment';

describe('Login', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Login],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('does not submit when the form is invalid', () => {
    const fixture = TestBed.createComponent(Login);
    fixture.detectChanges();

    fixture.componentInstance.submit();

    httpMock.expectNone(`${environment.apiBaseUrl}/auth/login`);
    expect(fixture.componentInstance.form.touched).toBe(true);
  });

  it('submits the form values and navigates to /agents on success', () => {
    const fixture = TestBed.createComponent(Login);
    fixture.detectChanges();
    const router = TestBed.inject(Router);
    const navigateSpy = vi.spyOn(router, 'navigateByUrl');

    fixture.componentInstance.form.setValue({
      tenantSlug: 'acme',
      email: 'dev@acme.com',
      password: 'hunter2',
    });
    fixture.componentInstance.submit();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/auth/login`);
    expect(req.request.body).toEqual({ tenantSlug: 'acme', email: 'dev@acme.com', password: 'hunter2' });
    req.flush({
      token: 't',
      expiresInSeconds: 3600,
      tenantId: 'tenant-1',
      tenantSlug: 'acme',
      userId: 'user-1',
      email: 'dev@acme.com',
      role: 'DEVELOPER',
    });

    expect(navigateSpy).toHaveBeenCalledWith('/agents');
    expect(fixture.componentInstance.submitting()).toBe(false);
  });

  it('shows an error message on a failed login', () => {
    const fixture = TestBed.createComponent(Login);
    fixture.detectChanges();

    fixture.componentInstance.form.setValue({
      tenantSlug: 'acme',
      email: 'dev@acme.com',
      password: 'wrong',
    });
    fixture.componentInstance.submit();

    httpMock
      .expectOne(`${environment.apiBaseUrl}/auth/login`)
      .flush({ message: 'Invalid credentials' }, { status: 401, statusText: 'Unauthorized' });

    expect(fixture.componentInstance.errorMessage()).toBe('Invalid tenant, email, or password.');
    expect(fixture.componentInstance.submitting()).toBe(false);
  });
});
