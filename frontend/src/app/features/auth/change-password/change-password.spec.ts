import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { ChangePassword } from './change-password';
import { AuthService } from '../../../core/services/auth.service';
import { environment } from '../../../../environments/environment';

describe('ChangePassword', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [ChangePassword],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  it('does not submit when the form is invalid', () => {
    const fixture = TestBed.createComponent(ChangePassword);
    fixture.detectChanges();

    fixture.componentInstance.submit();

    httpMock.expectNone(`${environment.apiBaseUrl}/auth/change-password`);
    expect(fixture.componentInstance.form.touched).toBe(true);
  });

  it('does not submit when the new password fails the strength policy', () => {
    const fixture = TestBed.createComponent(ChangePassword);
    fixture.detectChanges();
    fixture.componentInstance.form.setValue({
      currentPassword: 'Tmp9!xyz',
      newPassword: 'allletters',
      confirmNewPassword: 'allletters',
    });

    fixture.componentInstance.submit();

    httpMock.expectNone(`${environment.apiBaseUrl}/auth/change-password`);
  });

  it('does not submit when the confirmation does not match', () => {
    const fixture = TestBed.createComponent(ChangePassword);
    fixture.detectChanges();
    fixture.componentInstance.form.setValue({
      currentPassword: 'Tmp9!xyz',
      newPassword: 'N3w!password',
      confirmNewPassword: 'something-else',
    });

    fixture.componentInstance.submit();

    httpMock.expectNone(`${environment.apiBaseUrl}/auth/change-password`);
    expect(fixture.componentInstance.form.errors?.['passwordsMismatch']).toBe(true);
  });

  it('submits and navigates to /agents on success', () => {
    const fixture = TestBed.createComponent(ChangePassword);
    fixture.detectChanges();
    const router = TestBed.inject(Router);
    const navigateSpy = vi.spyOn(router, 'navigateByUrl');
    fixture.componentInstance.form.setValue({
      currentPassword: 'Tmp9!xyz',
      newPassword: 'N3w!password',
      confirmNewPassword: 'N3w!password',
    });

    fixture.componentInstance.submit();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/auth/change-password`);
    expect(req.request.body).toEqual({ currentPassword: 'Tmp9!xyz', newPassword: 'N3w!password' });
    req.flush({
      token: 'new-jwt',
      expiresInSeconds: 3600,
      tenantId: 'tenant-1',
      tenantSlug: 'acme',
      userId: 'user-1',
      email: 'dev@acme.com',
      role: 'DEVELOPER',
      mustChangePassword: false,
    });

    expect(navigateSpy).toHaveBeenCalledWith('/agents');
    expect(TestBed.inject(AuthService).mustChangePassword()).toBe(false);
  });

  it('shows the server error message when the current password is wrong', () => {
    const fixture = TestBed.createComponent(ChangePassword);
    fixture.detectChanges();
    fixture.componentInstance.form.setValue({
      currentPassword: 'wrong',
      newPassword: 'N3w!password',
      confirmNewPassword: 'N3w!password',
    });

    fixture.componentInstance.submit();

    httpMock
      .expectOne(`${environment.apiBaseUrl}/auth/change-password`)
      .flush({ message: 'Current password is incorrect' }, { status: 400, statusText: 'Bad Request' });

    expect(fixture.componentInstance.errorMessage()).toBe('Current password is incorrect');
    expect(fixture.componentInstance.submitting()).toBe(false);
  });
});
