import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { UserService } from './user.service';
import { environment } from '../../../environments/environment';

describe('UserService', () => {
  let service: UserService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(UserService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('list() GETs /users', () => {
    service.list().subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/users`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('create() POSTs {email, name, role} to /users with no password field', () => {
    service.create({ email: 'dev@acme.com', name: 'Dev User', role: 'DEVELOPER' }).subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/users`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ email: 'dev@acme.com', name: 'Dev User', role: 'DEVELOPER' });
    expect(req.request.body.password).toBeUndefined();
    req.flush({});
  });

  it('updateRole() PATCHes {role} to /users/{id}/role', () => {
    service.updateRole('user-1', { role: 'ADMIN' }).subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/users/user-1/role`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ role: 'ADMIN' });
    req.flush({});
  });

  it('delete() DELETEs /users/{id}', () => {
    service.delete('user-1').subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/users/user-1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
