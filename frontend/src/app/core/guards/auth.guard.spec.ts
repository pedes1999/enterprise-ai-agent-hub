import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { authGuard } from './auth.guard';
import { AuthService } from '../services/auth.service';

describe('authGuard', () => {
  function runGuard(url = '/agents'): boolean | UrlTree {
    return TestBed.runInInjectionContext(() => authGuard({} as any, { url } as any)) as boolean | UrlTree;
  }

  it('allows navigation when authenticated and no password change is pending', () => {
    const authService = { isAuthenticated: () => true, mustChangePassword: () => false } as Partial<AuthService>;
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: authService }],
    });

    expect(runGuard()).toBe(true);
  });

  it('redirects to /login when not authenticated', () => {
    const authService = { isAuthenticated: () => false, mustChangePassword: () => false } as Partial<AuthService>;
    const router = TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: authService }],
    }).inject(Router);
    const expectedTree = router.parseUrl('/login');

    const result = runGuard();

    expect(result).not.toBe(true);
    expect((result as UrlTree).toString()).toBe(expectedTree.toString());
  });

  it('redirects to /change-password when authenticated but still on a temporary password', () => {
    const authService = { isAuthenticated: () => true, mustChangePassword: () => true } as Partial<AuthService>;
    const router = TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: authService }],
    }).inject(Router);
    const expectedTree = router.parseUrl('/change-password');

    const result = runGuard('/agents');

    expect(result).not.toBe(true);
    expect((result as UrlTree).toString()).toBe(expectedTree.toString());
  });

  it('does not redirect away from /change-password itself, avoiding a loop', () => {
    const authService = { isAuthenticated: () => true, mustChangePassword: () => true } as Partial<AuthService>;
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: authService }],
    });

    expect(runGuard('/change-password')).toBe(true);
  });
});
