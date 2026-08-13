import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { History } from './history';
import { environment } from '../../../environments/environment';
import { AgentExecutionStatusResponse } from '../../core/models/agent.model';

describe('History', () => {
  let httpMock: HttpTestingController;

  const exec: AgentExecutionStatusResponse = {
    id: 'exec-1',
    status: 'SUCCEEDED',
    llmProvider: 'ANTHROPIC',
    agentSlug: 'code-reviewer',
    prompt: null,
    repositoryUrl: null,
    repositoryBranch: null,
    inputParameters: null,
    reply: 'done',
    toolWasUsed: true,
    errorMessage: null,
    createdAt: '2026-01-01T00:00:00Z',
    startedAt: '2026-01-01T00:00:01Z',
    completedAt: '2026-01-01T00:00:05Z',
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [History],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('loads the first page with default page/size and no status filter', () => {
    const fixture = TestBed.createComponent(History);
    fixture.detectChanges();

    const req = httpMock.expectOne(
      (r) => r.url === `${environment.apiBaseUrl}/agents/executions` && r.params.get('page') === '0' && r.params.get('size') === '20',
    );
    expect(req.request.params.has('status')).toBe(false);
    req.flush({ content: [exec], page: { size: 20, number: 0, totalElements: 1, totalPages: 1 } });

    expect(fixture.componentInstance.page()?.content.length).toBe(1);
    expect(fixture.componentInstance.loading()).toBe(false);
  });

  it('reloads at page 0 with the status param when the filter changes', () => {
    const fixture = TestBed.createComponent(History);
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url === `${environment.apiBaseUrl}/agents/executions`).flush({
      content: [],
      page: { size: 20, number: 0, totalElements: 0, totalPages: 0 },
    });

    fixture.componentInstance.statusFilter = 'FAILED';
    fixture.componentInstance.onFilterChange();

    const req = httpMock.expectOne(
      (r) => r.url === `${environment.apiBaseUrl}/agents/executions` && r.params.get('status') === 'FAILED',
    );
    req.flush({ content: [], page: { size: 20, number: 0, totalElements: 0, totalPages: 0 } });
  });

  it('nextPage() advances the page and refetches', () => {
    const fixture = TestBed.createComponent(History);
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url === `${environment.apiBaseUrl}/agents/executions`).flush({
      content: [exec],
      page: { size: 20, number: 0, totalElements: 40, totalPages: 2 },
    });

    fixture.componentInstance.nextPage();

    const req = httpMock.expectOne(
      (r) => r.url === `${environment.apiBaseUrl}/agents/executions` && r.params.get('page') === '1',
    );
    req.flush({ content: [], page: { size: 20, number: 1, totalElements: 40, totalPages: 2 } });
    expect(fixture.componentInstance.currentPage()).toBe(1);
  });

  it('nextPage() is a no-op on the last page', () => {
    const fixture = TestBed.createComponent(History);
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url === `${environment.apiBaseUrl}/agents/executions`).flush({
      content: [exec],
      page: { size: 20, number: 0, totalElements: 1, totalPages: 1 },
    });

    fixture.componentInstance.nextPage();

    httpMock.expectNone((r) => r.url === `${environment.apiBaseUrl}/agents/executions` && r.params.get('page') === '1');
  });

  it('shows an error banner when loading fails', () => {
    const fixture = TestBed.createComponent(History);
    fixture.detectChanges();

    httpMock
      .expectOne((r) => r.url === `${environment.apiBaseUrl}/agents/executions`)
      .flush({ message: 'Failed to load.' }, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.componentInstance.loadError()).toBe('Failed to load.');
    expect(fixture.componentInstance.loading()).toBe(false);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Failed to load.');
  });
});
