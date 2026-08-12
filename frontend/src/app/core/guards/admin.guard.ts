import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

/** Guards team-management routes on role, not just authentication -- DEVELOPER/READONLY are authenticated but must not reach these screens. */
export const adminGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  if (authService.isAdmin()) {
    return true;
  }
  return inject(Router).parseUrl('/agents');
};
