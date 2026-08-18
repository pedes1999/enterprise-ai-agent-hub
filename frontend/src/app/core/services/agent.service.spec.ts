import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
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

  it('cancel() POSTs /agents/executions/{id}/cancel', () => {
    service.cancel('exec-1').subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/exec-1/cancel`);
    expect(req.request.method).toBe('POST');
    req.flush(null);
  });

  describe('streamExecution', () => {
    /** Feeds the given raw chunks through a ReadableStream, exactly as fetch() would deliver them off the socket. */
    function mockFetchStreaming(chunks: string[], status = 200) {
      const encoder = new TextEncoder();
      const body = new ReadableStream<Uint8Array>({
        start(controller) {
          for (const chunk of chunks) {
            controller.enqueue(encoder.encode(chunk));
          }
          controller.close();
        },
      });
      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue({ ok: status === 200, status, body } as unknown as Response),
      );
    }

    async function collect(id: string) {
      const events = [];
      for await (const event of service.streamExecution(id, new AbortController().signal)) {
        events.push(event);
      }
      return events;
    }

    afterEach(() => vi.unstubAllGlobals());

    it('decodes status and tool events from well-formed frames', async () => {
      mockFetchStreaming([
        'event:status\ndata:{"id":"exec-1","status":"RUNNING"}\n\n',
        'event:tool\ndata:{"toolName":"git_clone","outcome":"SUCCESS"}\n\n',
      ]);

      const events = await collect('exec-1');

      expect(events.length).toBe(2);
      expect(events[0].event).toBe('status');
      expect((events[0].data as { status: string }).status).toBe('RUNNING');
      expect(events[1].event).toBe('tool');
      expect((events[1].data as { toolName: string }).toolName).toBe('git_clone');
    });

    it('reassembles an event split across chunk boundaries', async () => {
      // The failure this guards: a chunk can end anywhere, including
      // mid-JSON. Parsing per-chunk instead of per-frame would drop or
      // corrupt any event unlucky enough to straddle the split.
      mockFetchStreaming(['event:sta', 'tus\ndata:{"id":"exec-1","stat', 'us":"SUCCEEDED"}\n\n']);

      const events = await collect('exec-1');

      expect(events.length).toBe(1);
      expect((events[0].data as { status: string }).status).toBe('SUCCEEDED');
    });

    it('yields several frames arriving in a single chunk', async () => {
      mockFetchStreaming(['event:tool\ndata:{"toolName":"a"}\n\nevent:tool\ndata:{"toolName":"b"}\n\n']);

      const events = await collect('exec-1');

      expect(events.map((e) => (e.data as { toolName: string }).toolName)).toEqual(['a', 'b']);
    });

    it('ignores frames that are not status/tool or carry unparseable data', async () => {
      mockFetchStreaming([
        ':heartbeat comment\n\n',
        'event:unknown\ndata:{"x":1}\n\n',
        'event:tool\ndata:not-json\n\n',
        'event:tool\ndata:{"toolName":"kept"}\n\n',
      ]);

      const events = await collect('exec-1');

      expect(events.length).toBe(1);
      expect((events[0].data as { toolName: string }).toolName).toBe('kept');
    });

    it('throws when the stream cannot be established', async () => {
      mockFetchStreaming([], 404);

      await expect(collect('exec-1')).rejects.toThrow('HTTP 404');
    });
  });
});
