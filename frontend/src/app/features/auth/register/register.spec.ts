import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { Register } from './register';
import { environment } from '../../../../environments/environment';

describe('Register', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [Register],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  it('does not submit when the form is invalid', () => {
    const fixture = TestBed.createComponent(Register);
    fixture.detectChanges();

    fixture.componentInstance.submit();

    httpMock.expectNone(`${environment.apiBaseUrl}/auth/register`);
    expect(fixture.componentInstance.form.touched).toBe(true);
  });

  it('submits the form values and navigates to /agents on success', () => {
    const fixture = TestBed.createComponent(Register);
    fixture.detectChanges();
    const router = TestBed.inject(Router);
    const navigateSpy = vi.spyOn(router, 'navigateByUrl');

    fixture.componentInstance.form.setValue({
      tenantName: 'Acme',
      tenantSlug: 'acme',
      email: 'admin@acme.com',
      password: 'hunter22',
    });
    fixture.componentInstance.submit();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/auth/register`);
    expect(req.request.body).toEqual({
      tenantName: 'Acme',
      tenantSlug: 'acme',
      email: 'admin@acme.com',
      password: 'hunter22',
    });
    req.flush({
      token: 't',
      expiresInSeconds: 3600,
      tenantId: 'tenant-1',
      tenantSlug: 'acme',
      userId: 'user-1',
      email: 'admin@acme.com',
      role: 'ADMIN',
    });

    expect(navigateSpy).toHaveBeenCalledWith('/agents');
  });

  it('shows an error message when the tenant slug is already taken', () => {
    const fixture = TestBed.createComponent(Register);
    fixture.detectChanges();

    fixture.componentInstance.form.setValue({
      tenantName: 'Acme',
      tenantSlug: 'acme',
      email: 'admin@acme.com',
      password: 'hunter22',
    });
    fixture.componentInstance.submit();

    httpMock
      .expectOne(`${environment.apiBaseUrl}/auth/register`)
      .flush({ message: 'Conflict' }, { status: 409, statusText: 'Conflict' });

    expect(fixture.componentInstance.errorMessage()).toBe('That organization slug is already taken.');
  });
});
