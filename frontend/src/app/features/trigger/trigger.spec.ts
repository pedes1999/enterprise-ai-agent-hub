import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, ActivatedRoute, convertToParamMap } from '@angular/router';
import { Trigger, POLL_INTERVAL_MS } from './trigger';
import { environment } from '../../../environments/environment';
import { AgentExecutionStatusResponse } from '../../core/models/agent.model';

/**
 * This app is zoneless (no zone.js dependency), so Angular's fakeAsync/tick
 * helpers -- which require zone-testing -- aren't available. Vitest's fake
 * timers intercept the same setTimeout/setInterval calls RxJS's async
 * scheduler uses under the hood, which is enough to drive timer(0, interval)
 * deterministically without zone.js.
 */
describe('Trigger', () => {
  let httpMock: HttpTestingController;

  afterEach(() => {
    vi.useRealTimers();
    httpMock.verify();
  });

  function statusResponse(status: string, overrides: Partial<AgentExecutionStatusResponse> = {}): AgentExecutionStatusResponse {
    return {
      id: 'exec-1',
      status: status as AgentExecutionStatusResponse['status'],
      llmProvider: 'ANTHROPIC',
      agentSlug: 'code-reviewer',
      prompt: 'hello',
      repositoryUrl: null,
      repositoryBranch: null,
      inputParameters: null,
      reply: null,
      toolWasUsed: null,
      errorMessage: null,
      createdAt: '2026-01-01T00:00:00Z',
      startedAt: null,
      completedAt: null,
      ...overrides,
    };
  }

  function createComponent(requiredInputs: string[] = []) {
    TestBed.configureTestingModule({
      imports: [Trigger],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ slug: 'code-reviewer' }) } },
        },
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(Trigger);
    fixture.detectChanges();

    httpMock.expectOne(`${environment.apiBaseUrl}/agents/definitions/code-reviewer`).flush({
      slug: 'code-reviewer',
      name: 'Code Reviewer',
      description: '',
      systemPrompt: '',
      toolNames: [],
      inputSourceType: null,
      requiredInputs,
    });
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/usage`).flush({ active: 1, limit: 5 });

    return fixture;
  }

  it('loads the agent definition and usage on init', () => {
    const fixture = createComponent();
    expect(fixture.componentInstance.definition()?.name).toBe('Code Reviewer');
    expect(fixture.componentInstance.usage()).toEqual({ active: 1, limit: 5 });
    expect(fixture.componentInstance.definitionLoading()).toBe(false);
  });

  it('shows an error banner when the agent definition fails to load', () => {
    TestBed.configureTestingModule({
      imports: [Trigger],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ slug: 'unknown-slug' }) } },
        },
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(Trigger);
    fixture.detectChanges();

    httpMock
      .expectOne(`${environment.apiBaseUrl}/agents/definitions/unknown-slug`)
      .flush({ message: 'Unknown or inactive agent: unknown-slug' }, { status: 400, statusText: 'Bad Request' });
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/usage`).flush({ active: 0, limit: 5 });

    expect(fixture.componentInstance.definitionError()).toBe('Unknown or inactive agent: unknown-slug');
    expect(fixture.componentInstance.definitionLoading()).toBe(false);
  });

  it('shows a confirmation banner immediately when the execution is accepted', () => {
    const fixture = createComponent();
    fixture.componentInstance.submit();

    httpMock.expectOne(`${environment.apiBaseUrl}/agents/execute`).flush({ executionId: 'exec-1', status: 'QUEUED' });
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/usage`).flush({ active: 2, limit: 5 });

    expect(fixture.componentInstance.confirmationMessage()).toBe('Execution exec-1 queued.');
  });

  it('shows an error banner and stops polling if a poll request fails', async () => {
    vi.useFakeTimers();
    const fixture = createComponent();
    fixture.componentInstance.submit();

    httpMock.expectOne(`${environment.apiBaseUrl}/agents/execute`).flush({ executionId: 'exec-1', status: 'QUEUED' });
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/usage`).flush({ active: 2, limit: 5 });

    await vi.advanceTimersByTimeAsync(0);
    httpMock
      .expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1`)
      .flush({ message: 'No execution with id exec-1' }, { status: 404, statusText: 'Not Found' });

    expect(fixture.componentInstance.pollError()).toBe('No execution with id exec-1');

    // The poll observable errored out (terminated), so no further requests should fire.
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3);
    httpMock.expectNone(`${environment.apiBaseUrl}/agents/executions/exec-1`);

    fixture.destroy();
  });

  it('polls at the configured interval, applies each update, and stops on a terminal status', async () => {
    vi.useFakeTimers();
    const fixture = createComponent();
    fixture.componentInstance.submit();

    httpMock.expectOne(`${environment.apiBaseUrl}/agents/execute`).flush({ executionId: 'exec-1', status: 'QUEUED' });
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/usage`).flush({ active: 2, limit: 5 });

    // Immediate poll fires at t=0 via timer(0, interval).
    await vi.advanceTimersByTimeAsync(0);
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1`).flush(statusResponse('QUEUED'));
    expect(fixture.componentInstance.execution()?.status).toBe('QUEUED');

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1`).flush(statusResponse('RUNNING'));
    expect(fixture.componentInstance.execution()?.status).toBe('RUNNING');

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    httpMock
      .expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1`)
      .flush(statusResponse('SUCCEEDED', { reply: 'Looks good.' }));
    expect(fixture.componentInstance.execution()?.status).toBe('SUCCEEDED');

    // Polling must stop once a terminal status is reached -- no further
    // requests should fire even after more time passes (the runaway-poll bug class).
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3);
    httpMock.expectNone(`${environment.apiBaseUrl}/agents/executions/exec-1`);

    fixture.destroy();
  });

  it('stops polling when the component is destroyed mid-poll', async () => {
    vi.useFakeTimers();
    const fixture = createComponent();
    fixture.componentInstance.submit();

    httpMock.expectOne(`${environment.apiBaseUrl}/agents/execute`).flush({ executionId: 'exec-1', status: 'QUEUED' });
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/usage`).flush({ active: 2, limit: 5 });

    await vi.advanceTimersByTimeAsync(0);
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1`).flush(statusResponse('QUEUED'));

    fixture.destroy();

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 2);
    httpMock.expectNone(`${environment.apiBaseUrl}/agents/executions/exec-1`);
  });

  it('shows a rate-limit error on a 429 response', () => {
    const fixture = createComponent();
    fixture.componentInstance.submit();

    httpMock
      .expectOne(`${environment.apiBaseUrl}/agents/execute`)
      .flush({ message: 'Concurrent execution limit reached (5).' }, { status: 429, statusText: 'Too Many Requests' });

    expect(fixture.componentInstance.errorKind()).toBe('rate-limit');
  });

  it('shows a required-inputs error on a 400 "Missing required input" response', () => {
    const fixture = createComponent();
    fixture.componentInstance.submit();

    httpMock
      .expectOne(`${environment.apiBaseUrl}/agents/execute`)
      .flush({ message: 'Missing required input(s): repositoryUrl' }, { status: 400, statusText: 'Bad Request' });

    expect(fixture.componentInstance.errorKind()).toBe('required-inputs');
    expect(fixture.componentInstance.errorMessage()).toBe('Missing required input(s): repositoryUrl');
  });

  it('renders no fields, and no prompt/repositoryUrl in the request, for an agent with an empty requiredInputs', () => {
    const fixture = createComponent([]);
    expect(fixture.componentInstance.fields()).toEqual([]);

    fixture.componentInstance.submit();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/agents/execute`);
    expect(req.request.body).toEqual({
      prompt: null,
      agentSlug: 'code-reviewer',
      repositoryUrl: null,
      repositoryBranch: null,
      inputParameters: null,
    });
    req.flush({ executionId: 'exec-1', status: 'QUEUED' });
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/usage`).flush({ active: 1, limit: 5 });
  });

  it('renders only a Prompt field, and sends prompt, for an agent whose requiredInputs is just ["prompt"]', () => {
    const fixture = createComponent(['prompt']);
    expect(fixture.componentInstance.fields()).toEqual([{ key: 'prompt', label: 'Prompt' }]);

    fixture.componentInstance.fieldValues['prompt'] = 'What time is it?';
    fixture.componentInstance.submit();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/agents/execute`);
    expect(req.request.body).toEqual({
      prompt: 'What time is it?',
      agentSlug: 'code-reviewer',
      repositoryUrl: null,
      repositoryBranch: null,
      inputParameters: null,
    });
    req.flush({ executionId: 'exec-1', status: 'QUEUED' });
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/usage`).flush({ active: 1, limit: 5 });
  });

  it('renders a Repository URL field, and sends repositoryUrl, for an agent whose requiredInputs is ["repositoryUrl"]', () => {
    const fixture = createComponent(['repositoryUrl']);
    expect(fixture.componentInstance.fields()).toEqual([{ key: 'repositoryUrl', label: 'Repository URL' }]);

    fixture.componentInstance.fieldValues['repositoryUrl'] = 'https://github.com/octocat/Hello-World.git';
    fixture.componentInstance.submit();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/agents/execute`);
    expect(req.request.body).toEqual({
      prompt: null,
      agentSlug: 'code-reviewer',
      repositoryUrl: 'https://github.com/octocat/Hello-World.git',
      repositoryBranch: null,
      inputParameters: null,
    });
    req.flush({ executionId: 'exec-1', status: 'QUEUED' });
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/usage`).flush({ active: 1, limit: 5 });
  });

  it('shows the branch field alongside repositoryUrl, and sends repositoryBranch when filled in', () => {
    const fixture = createComponent(['repositoryUrl']);
    expect(fixture.componentInstance.showBranchField()).toBe(true);

    fixture.componentInstance.fieldValues['repositoryUrl'] = 'https://github.com/octocat/Hello-World.git';
    fixture.componentInstance.branchValue = 'feature/my-branch';
    fixture.componentInstance.submit();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/agents/execute`);
    expect(req.request.body).toEqual({
      prompt: null,
      agentSlug: 'code-reviewer',
      repositoryUrl: 'https://github.com/octocat/Hello-World.git',
      repositoryBranch: 'feature/my-branch',
      inputParameters: null,
    });
    req.flush({ executionId: 'exec-1', status: 'QUEUED' });
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/usage`).flush({ active: 1, limit: 5 });
  });

  it('hides the branch field for an agent whose requiredInputs has no repositoryUrl', () => {
    const fixture = createComponent(['prompt']);
    expect(fixture.componentInstance.showBranchField()).toBe(false);
  });

  it('renders a generic labeled field, and sends it via inputParameters, for a non-fixed requiredInputs key', () => {
    const fixture = createComponent(['text']);
    expect(fixture.componentInstance.fields()).toEqual([{ key: 'text', label: 'Text' }]);

    fixture.componentInstance.fieldValues['text'] = 'ticket body here';
    fixture.componentInstance.submit();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/agents/execute`);
    expect(req.request.body).toEqual({
      prompt: null,
      agentSlug: 'code-reviewer',
      repositoryUrl: null,
      repositoryBranch: null,
      inputParameters: { text: 'ticket body here' },
    });
    req.flush({ executionId: 'exec-1', status: 'QUEUED' });
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/usage`).flush({ active: 1, limit: 5 });
  });
});
