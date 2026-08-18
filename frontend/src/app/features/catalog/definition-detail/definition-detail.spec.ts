import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, ActivatedRoute, convertToParamMap } from '@angular/router';
import { DefinitionDetail } from './definition-detail';
import { AuthService } from '../../../core/services/auth.service';
import { environment } from '../../../../environments/environment';

describe('DefinitionDetail', () => {
  let httpMock: HttpTestingController;

  function configure(slug: string, isAdmin = false) {
    TestBed.configureTestingModule({
      imports: [DefinitionDetail],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: { isAdmin: () => isAdmin } },
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

  it('non-admin never requests knowledge-source binding data', () => {
    configure('ticket-resolver', false);
    const fixture = TestBed.createComponent(DefinitionDetail);
    fixture.detectChanges();

    httpMock.expectOne(`${environment.apiBaseUrl}/agents/definitions/ticket-resolver`).flush({
      slug: 'ticket-resolver',
      name: 'Ticket Resolver',
      description: '',
      systemPrompt: '',
      toolNames: [],
      inputSourceType: null,
      requiredInputs: [],
    });

    httpMock.expectNone(`${environment.apiBaseUrl}/knowledge-sources`);
    httpMock.expectNone(`${environment.apiBaseUrl}/knowledge-sources/agent-bindings/ticket-resolver`);
  });

  it('admin sees the currently bound knowledge source and can detach it', () => {
    configure('ticket-resolver', true);
    const fixture = TestBed.createComponent(DefinitionDetail);
    fixture.detectChanges();

    httpMock.expectOne(`${environment.apiBaseUrl}/agents/definitions/ticket-resolver`).flush({
      slug: 'ticket-resolver',
      name: 'Ticket Resolver',
      description: '',
      systemPrompt: '',
      toolNames: [],
      inputSourceType: null,
      requiredInputs: [],
    });
    httpMock.expectOne(`${environment.apiBaseUrl}/knowledge-sources`).flush([]);
    httpMock
      .expectOne(`${environment.apiBaseUrl}/knowledge-sources/agent-bindings/ticket-resolver`)
      .flush({ knowledgeSourceId: 'source-1', knowledgeSourceName: 'Internal API docs' });

    expect(fixture.componentInstance.binding()).toEqual({
      knowledgeSourceId: 'source-1',
      knowledgeSourceName: 'Internal API docs',
    });

    fixture.componentInstance.detachSource();
    httpMock.expectOne(`${environment.apiBaseUrl}/knowledge-sources/source-1/agent-bindings/ticket-resolver`).flush(null);

    expect(fixture.componentInstance.binding()).toBeNull();
  });

  it('admin can attach a knowledge source when none is bound yet', () => {
    configure('ticket-resolver', true);
    const fixture = TestBed.createComponent(DefinitionDetail);
    fixture.detectChanges();

    httpMock.expectOne(`${environment.apiBaseUrl}/agents/definitions/ticket-resolver`).flush({
      slug: 'ticket-resolver',
      name: 'Ticket Resolver',
      description: '',
      systemPrompt: '',
      toolNames: [],
      inputSourceType: null,
      requiredInputs: [],
    });
    httpMock
      .expectOne(`${environment.apiBaseUrl}/knowledge-sources`)
      .flush([{ id: 'source-1', name: 'Internal API docs', sourceType: 'upload', createdAt: '2026-01-01T00:00:00Z' }]);
    httpMock.expectOne(`${environment.apiBaseUrl}/knowledge-sources/agent-bindings/ticket-resolver`).flush(null);

    expect(fixture.componentInstance.binding()).toBeNull();

    fixture.componentInstance.selectedSourceId.set('source-1');
    fixture.componentInstance.attachSource();

    httpMock.expectOne(`${environment.apiBaseUrl}/knowledge-sources/source-1/agent-bindings/ticket-resolver`).flush(null);
    // attachSource() re-fetches the binding on success to reflect the new state
    httpMock
      .expectOne(`${environment.apiBaseUrl}/knowledge-sources/agent-bindings/ticket-resolver`)
      .flush({ knowledgeSourceId: 'source-1', knowledgeSourceName: 'Internal API docs' });

    expect(fixture.componentInstance.binding()?.knowledgeSourceId).toBe('source-1');
  });
});
