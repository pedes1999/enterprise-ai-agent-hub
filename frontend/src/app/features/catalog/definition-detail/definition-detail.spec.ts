import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, ActivatedRoute, convertToParamMap } from '@angular/router';
import { DefinitionDetail } from './definition-detail';
import { environment } from '../../../../environments/environment';

describe('DefinitionDetail', () => {
  let httpMock: HttpTestingController;

  function configure(slug: string) {
    TestBed.configureTestingModule({
      imports: [DefinitionDetail],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ slug }) } },
        },
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
  }

  afterEach(() => httpMock.verify());

  it('loads and renders the definition detail', () => {
    configure('code-reviewer');
    const fixture = TestBed.createComponent(DefinitionDetail);
    fixture.detectChanges();

    httpMock.expectOne(`${environment.apiBaseUrl}/agents/definitions/code-reviewer`).flush({
      slug: 'code-reviewer',
      name: 'Code Reviewer',
      description: 'Reviews PRs',
      systemPrompt: 'You are a reviewer.',
      toolNames: ['run_shell_command'],
      inputSourceType: 'REPOSITORY',
      requiredInputs: ['repositoryUrl'],
    });
    fixture.detectChanges();

    expect(fixture.componentInstance.definition()?.name).toBe('Code Reviewer');
    expect(fixture.componentInstance.notFound()).toBe(false);
    const text = (fixture.nativeElement as HTMLElement).textContent;
    expect(text).toContain('You are a reviewer.');
  });

  it('shows a not-found state on a 404', () => {
    configure('unknown-slug');
    const fixture = TestBed.createComponent(DefinitionDetail);
    fixture.detectChanges();

    httpMock
      .expectOne(`${environment.apiBaseUrl}/agents/definitions/unknown-slug`)
      .flush({ message: 'not found' }, { status: 404, statusText: 'Not Found' });

    expect(fixture.componentInstance.notFound()).toBe(true);
    expect(fixture.componentInstance.loading()).toBe(false);
  });
});
