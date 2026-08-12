import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { guestGuard } from './guest.guard';
import { AuthService } from '../services/auth.service';

describe('guestGuard', () => {
  function runGuard(): boolean | UrlTree {
    return TestBed.runInInjectionContext(() => guestGuard({} as any, {} as any)) as boolean | UrlTree;
  }

  it('allows navigation to login/register when there is no session', () => {
    const authService = { isAuthenticated: () => false } as Partial<AuthService>;
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: authService }],
    });

    expect(runGuard()).toBe(true);
  });

  it('redirects an already-authenticated user to /agents instead of showing login/register', () => {
    const authService = { isAuthenticated: () => true } as Partial<AuthService>;
    const router = TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: authService }],
    }).inject(Router);
    const expectedTree = router.parseUrl('/agents');

    const result = runGuard();

    expect(result).not.toBe(true);
    expect((result as UrlTree).toString()).toBe(expectedTree.toString());
  });
});
