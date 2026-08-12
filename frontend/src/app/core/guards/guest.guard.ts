import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

/**
 * Inverse of authGuard: keeps an already-authenticated user off
 * login/register. Without this, landing on '/' (which always redirects to
 * 'login' regardless of session state) rendered the login form UNDER the
 * app header -- app.html's header only checks isAuthenticated(), not which
 * route is active, so a still-logged-in user got the confusing combination
 * of the nav bar plus a login form, with every nav link still working
 * fine underneath it.
 */
export const guestGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  if (authService.isAuthenticated()) {
    return inject(Router).parseUrl('/agents');
  }
  return true;
};
