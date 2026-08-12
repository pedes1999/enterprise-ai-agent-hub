import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from '../services/auth.service';

describe('authInterceptor', () => {
  let httpMock: HttpTestingController;
  let http: HttpClient;

  function configure(authService: Partial<AuthService>) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: authService },
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
    http = TestBed.inject(HttpClient);
  }

  afterEach(() => httpMock.verify());

  it('attaches an Authorization header when a token is present', () => {
    configure({ token: () => 'jwt-token-abc' } as Partial<AuthService>);

    http.get('/agents/definitions').subscribe();

    const req = httpMock.expectOne('/agents/definitions');
    expect(req.request.headers.get('Authorization')).toBe('Bearer jwt-token-abc');
    req.flush({});
  });

  it('leaves the request unchanged when there is no token', () => {
    configure({ token: () => null } as Partial<AuthService>);

    http.get('/agents/definitions').subscribe();

    const req = httpMock.expectOne('/agents/definitions');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });
});
