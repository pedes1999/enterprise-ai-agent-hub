import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, ActivatedRoute, convertToParamMap } from '@angular/router';
import { ExecutionDetail } from './execution-detail';
import { environment } from '../../../../environments/environment';

describe('ExecutionDetail', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ExecutionDetail],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: 'exec-1' }) } },
        },
      ],
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('loads the execution and its tool-call trace', () => {
    const fixture = TestBed.createComponent(ExecutionDetail);
    fixture.detectChanges();

    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1`).flush({
      id: 'exec-1',
      status: 'SUCCEEDED',
      llmProvider: 'ANTHROPIC',
      agentSlug: 'code-reviewer',
      prompt: 'hi',
      repositoryUrl: null,
      repositoryBranch: null,
      inputParameters: null,
      reply: 'done',
      toolWasUsed: true,
      errorMessage: null,
      createdAt: '2026-01-01T00:00:00Z',
      startedAt: '2026-01-01T00:00:01Z',
      completedAt: '2026-01-01T00:00:05Z',
    });
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1/tool-executions`).flush([
      { toolName: 'run_shell_command', durationMs: 120, outcome: 'SUCCESS', errorMessage: null, createdAt: '2026-01-01T00:00:02Z' },
    ]);
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1/children`).flush([]);

    expect(fixture.componentInstance.execution()?.status).toBe('SUCCEEDED');
    expect(fixture.componentInstance.toolExecutions().length).toBe(1);
    expect(fixture.componentInstance.loading()).toBe(false);
  });

  it('shows an error banner when the execution fails to load', () => {
    const fixture = TestBed.createComponent(ExecutionDetail);
    fixture.detectChanges();

    httpMock
      .expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1`)
      .flush({ message: 'No execution with id exec-1' }, { status: 404, statusText: 'Not Found' });
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1/tool-executions`).flush([]);
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1/children`).flush([]);

    expect(fixture.componentInstance.loadError()).toBe('No execution with id exec-1');
    expect(fixture.componentInstance.loading()).toBe(false);
  });

  it('shows a trace-specific error banner when the tool-call trace fails to load', () => {
    const fixture = TestBed.createComponent(ExecutionDetail);
    fixture.detectChanges();

    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1`).flush({
      id: 'exec-1',
      status: 'SUCCEEDED',
      llmProvider: 'ANTHROPIC',
      agentSlug: 'code-reviewer',
      prompt: 'hi',
      repositoryUrl: null,
      repositoryBranch: null,
      inputParameters: null,
      reply: 'done',
      toolWasUsed: true,
      errorMessage: null,
      createdAt: '2026-01-01T00:00:00Z',
      startedAt: '2026-01-01T00:00:01Z',
      completedAt: '2026-01-01T00:00:05Z',
    });
    httpMock
      .expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1/tool-executions`)
      .flush({ message: 'Failed to load trace.' }, { status: 500, statusText: 'Server Error' });
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1/children`).flush([]);

    expect(fixture.componentInstance.traceError()).toBe('Failed to load trace.');
  });

  it('loads delegated child executions', () => {
    const fixture = TestBed.createComponent(ExecutionDetail);
    fixture.detectChanges();

    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1`).flush({
      id: 'exec-1',
      status: 'SUCCEEDED',
      llmProvider: 'ANTHROPIC',
      agentSlug: 'planner',
      prompt: 'plan it',
      repositoryUrl: null,
      repositoryBranch: null,
      inputParameters: null,
      reply: 'Delegated to ticket-resolver.',
      toolWasUsed: true,
      errorMessage: null,
      createdAt: '2026-01-01T00:00:00Z',
      startedAt: '2026-01-01T00:00:01Z',
      completedAt: '2026-01-01T00:00:05Z',
    });
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1/tool-executions`).flush([]);
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1/children`).flush([
      {
        id: 'exec-2',
        status: 'QUEUED',
        llmProvider: 'ANTHROPIC',
        agentSlug: 'ticket-resolver',
        prompt: 'fix the ticket',
        repositoryUrl: null,
        repositoryBranch: null,
        inputParameters: null,
        reply: null,
        toolWasUsed: null,
        errorMessage: null,
        createdAt: '2026-01-01T00:00:02Z',
        startedAt: null,
        completedAt: null,
        parentExecutionId: 'exec-1',
      },
    ]);

    expect(fixture.componentInstance.children().length).toBe(1);
    expect(fixture.componentInstance.children()[0].id).toBe('exec-2');
    expect(fixture.componentInstance.childrenError()).toBeNull();
  });

  it('shows a children-specific error banner when delegated executions fail to load', () => {
    const fixture = TestBed.createComponent(ExecutionDetail);
    fixture.detectChanges();

    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1`).flush({
      id: 'exec-1',
      status: 'SUCCEEDED',
      llmProvider: 'ANTHROPIC',
      agentSlug: 'planner',
      prompt: 'plan it',
      repositoryUrl: null,
      repositoryBranch: null,
      inputParameters: null,
      reply: 'done',
      toolWasUsed: true,
      errorMessage: null,
      createdAt: '2026-01-01T00:00:00Z',
      startedAt: '2026-01-01T00:00:01Z',
      completedAt: '2026-01-01T00:00:05Z',
    });
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1/tool-executions`).flush([]);
    httpMock
      .expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1/children`)
      .flush({ message: 'Failed to load delegated executions.' }, { status: 500, statusText: 'Server Error' });

    expect(fixture.componentInstance.childrenError()).toBe('Failed to load delegated executions.');
  });

  describe('canCancel', () => {
    it('is true for QUEUED and RUNNING, false for every terminal status', () => {
      const fixture = TestBed.createComponent(ExecutionDetail);
      const component = fixture.componentInstance;

      expect(component.canCancel('QUEUED')).toBe(true);
      expect(component.canCancel('RUNNING')).toBe(true);
      expect(component.canCancel('SUCCEEDED')).toBe(false);
      expect(component.canCancel('FAILED')).toBe(false);
      expect(component.canCancel('CANCELLED')).toBe(false);
    });
  });

  describe('cancel', () => {
    function loadExecution(fixture: ReturnType<typeof TestBed.createComponent<ExecutionDetail>>, status: string) {
      fixture.detectChanges();
      httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1`).flush({
        id: 'exec-1',
        status,
        llmProvider: 'ANTHROPIC',
        agentSlug: 'code-reviewer',
        prompt: 'hi',
        repositoryUrl: null,
        repositoryBranch: null,
        inputParameters: null,
        reply: null,
        toolWasUsed: null,
        errorMessage: null,
        createdAt: '2026-01-01T00:00:00Z',
        startedAt: null,
        completedAt: null,
      });
      httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1/tool-executions`).flush([]);
      httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1/children`).flush([]);
    }

    it('on success, re-fetches the execution instead of assuming the outcome', () => {
      const fixture = TestBed.createComponent(ExecutionDetail);
      loadExecution(fixture, 'QUEUED');

      fixture.componentInstance.cancel();
      expect(fixture.componentInstance.cancelling()).toBe(true);
      httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1/cancel`).flush(null);

      // The re-fetch this test is actually about: cancel() must not just set
      // status locally, since a RUNNING cancel is only a pending flag, not
      // an instant transition -- only the server knows which actually happened.
      httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1`).flush({
        id: 'exec-1',
        status: 'CANCELLED',
        llmProvider: 'ANTHROPIC',
        agentSlug: 'code-reviewer',
        prompt: 'hi',
        repositoryUrl: null,
        repositoryBranch: null,
        inputParameters: null,
        reply: null,
        toolWasUsed: null,
        errorMessage: null,
        createdAt: '2026-01-01T00:00:00Z',
        startedAt: null,
        completedAt: '2026-01-01T00:00:06Z',
      });

      expect(fixture.componentInstance.cancelling()).toBe(false);
      expect(fixture.componentInstance.execution()?.status).toBe('CANCELLED');
    });

    it('on failure, shows the error and stops the cancelling state without touching the execution', () => {
      const fixture = TestBed.createComponent(ExecutionDetail);
      loadExecution(fixture, 'RUNNING');

      fixture.componentInstance.cancel();
      httpMock
        .expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1/cancel`)
        .flush({ message: 'Execution exec-1 is already SUCCEEDED -- nothing to cancel.' }, { status: 409, statusText: 'Conflict' });

      expect(fixture.componentInstance.cancelling()).toBe(false);
      expect(fixture.componentInstance.cancelError()).toBe('Execution exec-1 is already SUCCEEDED -- nothing to cancel.');
      expect(fixture.componentInstance.execution()?.status).toBe('RUNNING');
    });
  });
});
