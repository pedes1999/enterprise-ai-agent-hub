import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Team } from './team';
import { AuthService } from '../../core/services/auth.service';
import { environment } from '../../../environments/environment';
import { UserSummary } from '../../core/models/user.model';

describe('Team', () => {
  let httpMock: HttpTestingController;

  const adminUser: UserSummary = {
    id: 'user-1',
    email: 'admin@acme.com',
    name: 'Admin User',
    role: 'ADMIN',
    createdAt: '2026-01-01T00:00:00Z',
  };
  const devUser: UserSummary = {
    id: 'user-2',
    email: 'dev@acme.com',
    name: 'Dev User',
    role: 'DEVELOPER',
    createdAt: '2026-01-02T00:00:00Z',
  };

  function createComponent() {
    const fixture = TestBed.createComponent(Team);
    fixture.detectChanges();
    httpMock.expectOne(`${environment.apiBaseUrl}/users`).flush([adminUser, devUser]);
    return fixture;
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Team],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { email: () => 'admin@acme.com' } },
      ],
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('loads the team member list', () => {
    const fixture = createComponent();
    expect(fixture.componentInstance.users().length).toBe(2);
    expect(fixture.componentInstance.loading()).toBe(false);
  });

  it('does not create a user when the form is incomplete', () => {
    const fixture = createComponent();
    fixture.componentInstance.newEmail = '';
    fixture.componentInstance.newName = 'Someone';
    fixture.componentInstance.createUser();
    httpMock.expectNone(`${environment.apiBaseUrl}/users`);
  });

  it('creates a user with no password field and shows the emailed-password message', () => {
    const fixture = createComponent();
    fixture.componentInstance.newEmail = 'new@acme.com';
    fixture.componentInstance.newName = 'New Person';
    fixture.componentInstance.newRole = 'READONLY';
    fixture.componentInstance.createUser();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/users`);
    expect(req.request.body).toEqual({ email: 'new@acme.com', name: 'New Person', role: 'READONLY' });
    req.flush({ id: 'user-3', email: 'new@acme.com', name: 'New Person', role: 'READONLY', createdAt: '2026-01-03T00:00:00Z' });

    httpMock.expectOne(`${environment.apiBaseUrl}/users`).flush([adminUser, devUser]);

    expect(fixture.componentInstance.createdMessage()).toContain('New Person');
    expect(fixture.componentInstance.createdMessage()).toContain('emailed');
    expect(fixture.componentInstance.newEmail).toBe('');
  });

  it('changeRole() PATCHes the new role, refreshes, and shows a success message', () => {
    const fixture = createComponent();
    fixture.componentInstance.changeRole(devUser, 'ADMIN');

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/users/user-2/role`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ role: 'ADMIN' });
    req.flush({ ...devUser, role: 'ADMIN' });

    httpMock.expectOne(`${environment.apiBaseUrl}/users`).flush([adminUser, { ...devUser, role: 'ADMIN' }]);

    expect(fixture.componentInstance.rowMessages['user-2']).toEqual({ kind: 'success', text: 'Role updated to ADMIN.' });
    expect(fixture.componentInstance.pendingUserId()).toBeNull();
  });

  it('changeRole() shows an error message and does not refresh on failure', () => {
    const fixture = createComponent();
    fixture.componentInstance.changeRole(devUser, 'ADMIN');

    httpMock
      .expectOne(`${environment.apiBaseUrl}/users/user-2/role`)
      .flush({ message: 'Cannot change your own role.' }, { status: 400, statusText: 'Bad Request' });

    expect(fixture.componentInstance.rowMessages['user-2']).toEqual({ kind: 'error', text: 'Cannot change your own role.' });
    expect(fixture.componentInstance.pendingUserId()).toBeNull();
  });

  it('removeUser() DELETEs the user and refreshes', () => {
    const fixture = createComponent();
    fixture.componentInstance.removeUser(devUser);

    httpMock.expectOne(`${environment.apiBaseUrl}/users/user-2`).flush(null);
    httpMock.expectOne(`${environment.apiBaseUrl}/users`).flush([adminUser]);

    expect(fixture.componentInstance.pendingUserId()).toBeNull();
  });

  it('removeUser() shows an error message on failure', () => {
    const fixture = createComponent();
    fixture.componentInstance.removeUser(devUser);

    httpMock
      .expectOne(`${environment.apiBaseUrl}/users/user-2`)
      .flush({ message: 'Failed to remove user.' }, { status: 500, statusText: 'Server Error' });

    expect(fixture.componentInstance.rowMessages['user-2']).toEqual({ kind: 'error', text: 'Failed to remove user.' });
  });

  it('shows a top-level error banner when the initial load fails', () => {
    const fixture = TestBed.createComponent(Team);
    fixture.detectChanges();

    httpMock
      .expectOne(`${environment.apiBaseUrl}/users`)
      .flush({ message: 'Access denied' }, { status: 403, statusText: 'Forbidden' });

    expect(fixture.componentInstance.loadError()).toBe('Access denied');
    expect(fixture.componentInstance.loading()).toBe(false);
  });
});
