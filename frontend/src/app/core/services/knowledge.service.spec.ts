import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { KnowledgeService } from './knowledge.service';
import { environment } from '../../../environments/environment';

describe('KnowledgeService', () => {
  let service: KnowledgeService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(KnowledgeService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('list() GETs /knowledge-sources', () => {
    service.list().subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/knowledge-sources`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('create() POSTs {name, sourceType}', () => {
    service.create({ name: 'Internal docs', sourceType: 'upload' }).subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/knowledge-sources`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ name: 'Internal docs', sourceType: 'upload' });
    req.flush({});
  });

  it('uploadDocument() POSTs a FormData body with no manual Content-Type header', () => {
    const file = new File(['hello world'], 'notes.txt', { type: 'text/plain' });

    service.uploadDocument('source-1', file).subscribe();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/knowledge-sources/source-1/documents`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeInstanceOf(FormData);
    expect((req.request.body as FormData).get('file')).toBe(file);
    // Content-Type is deliberately never set here -- the browser derives the
    // multipart boundary from the FormData body itself (see the service's
    // javadoc). Asserting its absence catches a regression where someone
    // "helpfully" adds an explicit header and silently breaks every upload.
    expect(req.request.headers.has('Content-Type')).toBe(false);
    req.flush({ knowledgeSourceId: 'source-1', documentName: 'notes.txt', chunkCount: 1 });
  });

  it('query() POSTs {query, topK} to /knowledge-sources/{id}/query', () => {
    service.query('source-1', { query: 'how does X work', topK: 3 }).subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/knowledge-sources/source-1/query`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ query: 'how does X work', topK: 3 });
    req.flush([]);
  });

  it('getBindingForAgent() GETs /knowledge-sources/agent-bindings/{agentSlug}', () => {
    service.getBindingForAgent('ticket-resolver').subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/knowledge-sources/agent-bindings/ticket-resolver`);
    expect(req.request.method).toBe('GET');
    req.flush(null);
  });

  it('attachToAgent() PUTs /knowledge-sources/{id}/agent-bindings/{agentSlug}', () => {
    service.attachToAgent('source-1', 'ticket-resolver').subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/knowledge-sources/source-1/agent-bindings/ticket-resolver`);
    expect(req.request.method).toBe('PUT');
    req.flush(null);
  });

  it('detachFromAgent() DELETEs /knowledge-sources/{id}/agent-bindings/{agentSlug}', () => {
    service.detachFromAgent('source-1', 'ticket-resolver').subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/knowledge-sources/source-1/agent-bindings/ticket-resolver`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
