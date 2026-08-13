import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

/**
 * Protects every route except login/register -- redirects to login if
 * there's no valid token. Also redirects an admin-invited user who's still
 * on their temporary password to /change-password, mirroring the backend's
 * PasswordChangeRequiredFilter: the API would 403 every other endpoint for
 * them anyway, so there's nothing useful to show on any other route until
 * that's done.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const authService = inject(AuthService);
  const router = inject(Router);
  if (!authService.isAuthenticated()) {
    return router.parseUrl('/login');
  }
  if (authService.mustChangePassword() && !state.url.startsWith('/change-password')) {
    return router.parseUrl('/change-password');
  }
  return true;
};
