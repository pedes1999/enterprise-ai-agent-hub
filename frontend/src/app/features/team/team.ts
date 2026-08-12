import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { UserService } from '../../core/services/user.service';
import { AuthService } from '../../core/services/auth.service';
import { Role } from '../../core/models/auth.model';
import { UserSummary } from '../../core/models/user.model';

interface RowMessage {
  kind: 'success' | 'error';
  text: string;
}

@Component({
  selector: 'app-team',
  imports: [FormsModule],
  templateUrl: './team.html',
  styleUrl: './team.css',
})
export class Team implements OnInit {
  private readonly userService = inject(UserService);
  private readonly authService = inject(AuthService);

  readonly users = signal<UserSummary[]>([]);
  readonly loading = signal(true);
  readonly loadError = signal<string | null>(null);
  readonly currentUserEmail = this.authService.email;

  newEmail = '';
  newName = '';
  newRole: Role = 'DEVELOPER';

  readonly creating = signal(false);
  readonly createError = signal<string | null>(null);
  readonly createdMessage = signal<string | null>(null);

  readonly pendingUserId = signal<string | null>(null);
  readonly rowMessages: Record<string, RowMessage> = {};

  ngOnInit(): void {
    this.refresh();
  }

  private refresh(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.userService.list().subscribe({
      next: (list) => {
        this.users.set(list);
        this.loading.set(false);
      },
      error: (err) => {
        this.loadError.set(err.error?.message ?? 'Failed to load team members.');
        this.loading.set(false);
      },
    });
  }

  createUser(): void {
    if (!this.newEmail || !this.newName) {
      return;
    }
    this.creating.set(true);
    this.createError.set(null);
    this.createdMessage.set(null);

    this.userService.create({ email: this.newEmail, name: this.newName, role: this.newRole }).subscribe({
      next: (created) => {
        this.creating.set(false);
        this.createdMessage.set(`${created.name} was invited. A temporary password was emailed to ${created.email}.`);
        this.newEmail = '';
        this.newName = '';
        this.newRole = 'DEVELOPER';
        this.refresh();
      },
      error: (err) => {
        this.creating.set(false);
        this.createError.set(err.error?.message ?? 'Failed to create user.');
      },
    });
  }

  changeRole(user: UserSummary, role: Role): void {
    this.pendingUserId.set(user.id);
    delete this.rowMessages[user.id];
    this.userService.updateRole(user.id, { role }).subscribe({
      next: () => {
        this.pendingUserId.set(null);
        this.rowMessages[user.id] = { kind: 'success', text: `Role updated to ${role}.` };
        this.refresh();
      },
      error: (err) => {
        this.pendingUserId.set(null);
        this.rowMessages[user.id] = { kind: 'error', text: err.error?.message ?? 'Failed to update role.' };
      },
    });
  }

  removeUser(user: UserSummary): void {
    this.pendingUserId.set(user.id);
    delete this.rowMessages[user.id];
    this.userService.delete(user.id).subscribe({
      next: () => {
        this.pendingUserId.set(null);
        this.refresh();
      },
      error: (err) => {
        this.pendingUserId.set(null);
        this.rowMessages[user.id] = { kind: 'error', text: err.error?.message ?? 'Failed to remove user.' };
      },
    });
  }
}
