import { Component, inject, signal } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';
import { strongPasswordValidator } from '../../../shared/validators/password.validator';
import { extractErrorMessage } from '../../../shared/http/error-message';

@Component({
  selector: 'app-register',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './register.html',
  styleUrl: '../login/login.css',
})
export class Register {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  readonly submitting = signal(false);
  readonly errorMessage = signal<string | null>(null);

  readonly form = this.fb.nonNullable.group({
    tenantName: ['', Validators.required],
    tenantSlug: ['', Validators.required],
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, strongPasswordValidator]],
  });

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.submitting.set(true);
    this.errorMessage.set(null);

    this.authService.register(this.form.getRawValue()).subscribe({
      next: () => {
        this.submitting.set(false);
        this.router.navigateByUrl('/agents');
      },
      error: (err) => {
        this.submitting.set(false);
        if (err.status === 409) {
          this.errorMessage.set('That organization slug is already taken.');
        } else if (err.status === 400) {
          this.errorMessage.set(extractErrorMessage(err, 'Registration failed. Please try again.'));
        } else {
          this.errorMessage.set('Registration failed. Please try again.');
        }
      },
    });
  }
}
