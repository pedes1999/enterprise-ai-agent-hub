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

    expect(fixture.componentInstance.execution()?.status).toBe('SUCCEEDED');
    expect(fixture.componentInstance.toolExecutions().length).toBe(1);
    expect(fixture.componentInstance.loading()).toBe(false);
  });
});
