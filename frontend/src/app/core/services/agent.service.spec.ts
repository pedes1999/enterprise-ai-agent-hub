import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AgentService } from './agent.service';
import { environment } from '../../../environments/environment';

describe('AgentService', () => {
  let service: AgentService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AgentService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('listDefinitions() GETs /agents/definitions', () => {
    service.listDefinitions().subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/agents/definitions`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('getDefinition() GETs /agents/definitions/{slug}', () => {
    service.getDefinition('code-reviewer').subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/agents/definitions/code-reviewer`);
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('execute() POSTs the trigger request body to /agents/execute', () => {
    const request = {
      prompt: 'hello',
      agentSlug: 'code-reviewer',
      repositoryUrl: 'https://github.com/acme/repo',
      repositoryBranch: 'main',
      inputParameters: { branch: 'main' },
      maxTokens: null,
    };
    service.execute(request).subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/agents/execute`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(request);
    req.flush({ executionId: 'exec-1', status: 'QUEUED' });
  });

  it('getExecution() GETs /agents/executions/{id}', () => {
    service.getExecution('exec-1').subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1`);
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('listExecutions() GETs /agents/executions with page/size params', () => {
    service.listExecutions(0, 20).subscribe();
    const req = httpMock.expectOne(
      (r) => r.url === `${environment.apiBaseUrl}/agents/executions` && r.params.get('page') === '0' && r.params.get('size') === '20',
    );
    expect(req.request.method).toBe('GET');
    expect(req.request.params.has('status')).toBe(false);
    req.flush({ content: [], page: { size: 20, number: 0, totalElements: 0, totalPages: 0 } });
  });

  it('listExecutions() includes the status param when provided', () => {
    service.listExecutions(0, 20, 'FAILED').subscribe();
    const req = httpMock.expectOne(
      (r) => r.url === `${environment.apiBaseUrl}/agents/executions` && r.params.get('status') === 'FAILED',
    );
    req.flush({ content: [], page: { size: 20, number: 0, totalElements: 0, totalPages: 0 } });
  });

  it('getToolExecutions() GETs /agents/executions/{id}/tool-executions', () => {
    service.getToolExecutions('exec-1').subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1/tool-executions`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('getChildren() GETs /agents/executions/{id}/children', () => {
    service.getChildren('exec-1').subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1/children`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('getUsage() GETs /agents/executions/usage', () => {
    service.getUsage().subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/usage`);
    expect(req.request.method).toBe('GET');
    req.flush({ active: 1, limit: 5 });
  });
});
