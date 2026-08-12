import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { adminGuard } from './core/guards/admin.guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'login' },
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login/login').then((m) => m.Login),
  },
  {
    path: 'register',
    loadComponent: () => import('./features/auth/register/register').then((m) => m.Register),
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
    path: 'credentials',
    canActivate: [authGuard, adminGuard],
    loadComponent: () => import('./features/credentials/credentials').then((m) => m.Credentials),
  },
  {
    path: 'team',
    canActivate: [authGuard, adminGuard],
    loadComponent: () => import('./features/team/team').then((m) => m.Team),
  },
  { path: '**', redirectTo: 'login' },
];
