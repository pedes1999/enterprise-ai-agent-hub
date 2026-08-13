import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';

describe('App', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('does not render the header when unauthenticated', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    const header = (fixture.nativeElement as HTMLElement).querySelector('.app-header');
    expect(header).toBeNull();
  });

  function storeSession(role: 'ADMIN' | 'DEVELOPER' | 'READONLY') {
    localStorage.setItem(
      'auth.session',
      JSON.stringify({
        token: 'fake-token',
        tenantSlug: 'acme',
        email: `${role.toLowerCase()}@acme.com`,
        role,
        expiresAt: Date.now() + 60_000,
      }),
    );
  }

  it('shows Credentials but not Team for a DEVELOPER', () => {
    storeSession('DEVELOPER');
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    const links = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('a')).map((a) => a.getAttribute('routerLink'));
    expect(links).toContain('/credentials');
    expect(links).not.toContain('/team');
  });

  it('shows both Credentials and Team for an ADMIN', () => {
    storeSession('ADMIN');
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    const links = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('a')).map((a) => a.getAttribute('routerLink'));
    expect(links).toContain('/credentials');
    expect(links).toContain('/team');
  });

  it('hides both Credentials and Team for a READONLY user', () => {
    storeSession('READONLY');
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    const links = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('a')).map((a) => a.getAttribute('routerLink'));
    expect(links).not.toContain('/credentials');
    expect(links).not.toContain('/team');
  });
});
