import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { adminGuard } from './core/guards/admin.guard';
import { guestGuard } from './core/guards/guest.guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'login' },
  {
    path: 'login',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/login/login').then((m) => m.Login),
  },
  {
    path: 'register',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/register/register').then((m) => m.Register),
  },
  {
    // authGuard itself redirects here whenever mustChangePassword() is true
    // (see its javadoc) -- this route just needs to be reachable once that
    // happens, not gated any further.
    path: 'change-password',
    canActivate: [authGuard],
    loadComponent: () => import('./features/auth/change-password/change-password').then((m) => m.ChangePassword),
  },
  {
    path: 'agents',
    canActivate: [authGuard],
    loadComponent: () => import('./features/catalog/catalog').then((m) => m.Catalog),
  },
  {
    path: 'agents/:slug',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/catalog/definition-detail/definition-detail').then((m) => m.DefinitionDetail),
  },
  {
    path: 'agents/:slug/trigger',
    canActivate: [authGuard],
    loadComponent: () => import('./features/trigger/trigger').then((m) => m.Trigger),
  },
  {
    path: 'executions',
    canActivate: [authGuard],
    loadComponent: () => import('./features/history/history').then((m) => m.History),
  },
  {
    path: 'executions/:id',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/history/execution-detail/execution-detail').then((m) => m.ExecutionDetail),
  },
  {
    // ADMIN and DEVELOPER can each bring their own vendor credential (see
    // VendorCredentialController) -- only the page's "Team credentials"
    // section is admin-only, gated client-side via authService.isAdmin().
    path: 'credentials',
    canActivate: [authGuard],
    loadComponent: () => import('./features/credentials/credentials').then((m) => m.Credentials),
  },
  {
    path: 'team',
    canActivate: [authGuard, adminGuard],
    loadComponent: () => import('./features/team/team').then((m) => m.Team),
  },
  { path: '**', redirectTo: 'login' },
];
