import { Component, inject, signal } from '@angular/core';
import { NavigationStart, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/services/auth.service';
import { UsageIndicator } from './shared/components/usage-indicator/usage-indicator';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, UsageIndicator],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  readonly mobileMenuOpen = signal(false);

  constructor() {
    // Collapse the mobile drawer automatically on every navigation
    // (including a link tap inside the drawer itself), so it never stays
    // open covering the page the user just chose.
    this.router.events.subscribe((event) => {
      if (event instanceof NavigationStart) {
        this.mobileMenuOpen.set(false);
      }
    });
  }

  toggleMobileMenu(): void {
    this.mobileMenuOpen.update((open) => !open);
  }

  logout(): void {
    this.authService.logout();
    this.router.navigateByUrl('/login');
  }
}
