import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { adminGuard } from './admin.guard';
import { AuthService } from '../services/auth.service';

describe('adminGuard', () => {
  function runGuard(): boolean | UrlTree {
    return TestBed.runInInjectionContext(() => adminGuard({} as any, {} as any)) as boolean | UrlTree;
  }

  it('allows navigation when the session role is ADMIN', () => {
    const authService = { isAdmin: () => true } as Partial<AuthService>;
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: authService }],
    });

    expect(runGuard()).toBe(true);
  });

  it('redirects to /agents when the session role is not ADMIN', () => {
    const authService = { isAdmin: () => false } as Partial<AuthService>;
    const router = TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: authService }],
    }).inject(Router);
    const expectedTree = router.parseUrl('/agents');

    const result = runGuard();

    expect(result).not.toBe(true);
    expect((result as UrlTree).toString()).toBe(expectedTree.toString());
  });
});
