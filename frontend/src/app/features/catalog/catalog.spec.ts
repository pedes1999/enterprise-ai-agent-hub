import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { Catalog } from './catalog';
import { environment } from '../../../environments/environment';

describe('Catalog', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Catalog],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('loads and renders agent definitions', () => {
    const fixture = TestBed.createComponent(Catalog);
    fixture.detectChanges();

    httpMock.expectOne(`${environment.apiBaseUrl}/agents/definitions`).flush([
      { slug: 'code-reviewer', name: 'Code Reviewer', description: 'Reviews PRs', toolNames: ['run_shell_command'] },
    ]);
    fixture.detectChanges();

    expect(fixture.componentInstance.definitions().length).toBe(1);
    expect(fixture.componentInstance.loading()).toBe(false);
    const text = (fixture.nativeElement as HTMLElement).textContent;
    expect(text).toContain('Code Reviewer');
    expect(text).toContain('code-reviewer');
  });

  it('shows an empty state and stops loading on error', () => {
    const fixture = TestBed.createComponent(Catalog);
    fixture.detectChanges();

    httpMock
      .expectOne(`${environment.apiBaseUrl}/agents/definitions`)
      .flush({ message: 'error' }, { status: 500, statusText: 'Server Error' });

    expect(fixture.componentInstance.loading()).toBe(false);
    expect(fixture.componentInstance.definitions().length).toBe(0);
  });
});
