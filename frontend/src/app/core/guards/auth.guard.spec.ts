import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { authGuard } from './auth.guard';
import { AuthService } from '../services/auth.service';

describe('authGuard', () => {
  function runGuard(): boolean | UrlTree {
    return TestBed.runInInjectionContext(() => authGuard({} as any, {} as any)) as boolean | UrlTree;
  }

  it('allows navigation when authenticated', () => {
    const authService = { isAuthenticated: () => true } as Partial<AuthService>;
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: authService }],
    });

    expect(runGuard()).toBe(true);
  });

  it('redirects to /login when not authenticated', () => {
    const authService = { isAuthenticated: () => false } as Partial<AuthService>;
    const router = TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: authService }],
    }).inject(Router);
    const expectedTree = router.parseUrl('/login');

    const result = runGuard();

    expect(result).not.toBe(true);
    expect((result as UrlTree).toString()).toBe(expectedTree.toString());
  });
});
