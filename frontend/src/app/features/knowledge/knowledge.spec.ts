import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Knowledge } from './knowledge';
import { environment } from '../../../environments/environment';
import { KnowledgeSourceSummary } from '../../core/models/knowledge.model';

describe('Knowledge', () => {
  let httpMock: HttpTestingController;

  const source: KnowledgeSourceSummary = {
    id: 'source-1',
    name: 'Internal API docs',
    sourceType: 'upload',
    createdAt: '2026-01-01T00:00:00Z',
  };

  function createComponent(sources: KnowledgeSourceSummary[] = [source]) {
    const fixture = TestBed.createComponent(Knowledge);
    fixture.detectChanges();
    httpMock.expectOne(`${environment.apiBaseUrl}/knowledge-sources`).flush(sources);
    return fixture;
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Knowledge],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('loads and lists knowledge sources on init', () => {
    const fixture = createComponent();

    expect(fixture.componentInstance.sources()).toEqual([source]);
    expect(fixture.componentInstance.loading()).toBe(false);
  });

  it('shows an error banner when the initial load fails', () => {
    const fixture = TestBed.createComponent(Knowledge);
    fixture.detectChanges();

    httpMock
      .expectOne(`${environment.apiBaseUrl}/knowledge-sources`)
      .flush({ message: 'Boom' }, { status: 500, statusText: 'Internal Server Error' });

    expect(fixture.componentInstance.loadError()).toBe('Boom');
  });

  it('creates a knowledge source and prepends it to the list', () => {
    const fixture = createComponent([]);
    const component = fixture.componentInstance;
    component.newName.set('New source');

    component.create();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/knowledge-sources`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ name: 'New source', sourceType: 'upload' });
    req.flush({ id: 'source-2', name: 'New source', sourceType: 'upload', createdAt: '2026-01-02T00:00:00Z' });

    expect(component.sources().map((s) => s.id)).toEqual(['source-2']);
    expect(component.newName()).toBe('');
  });

  it('does not create when the name is blank', () => {
    const fixture = createComponent([]);
    fixture.componentInstance.newName.set('   ');

    fixture.componentInstance.create();

    httpMock.expectNone((req) => req.method === 'POST' && req.url === `${environment.apiBaseUrl}/knowledge-sources`);
  });

  it('uploads a file and reports the chunk count', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;
    const file = new File(['hello'], 'notes.txt');
    component.onFileSelected(source.id, { target: { files: [file] } } as unknown as Event);

    component.upload(source.id);

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/knowledge-sources/${source.id}/documents`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeInstanceOf(FormData);
    req.flush({ knowledgeSourceId: source.id, documentName: 'notes.txt', chunkCount: 3 });

    expect(component.uploadMessages[source.id]).toEqual({
      kind: 'success',
      text: 'Uploaded "notes.txt" — 3 chunks added.',
    });
    expect(component.hasSelectedFile(source.id)).toBe(false);
  });

  it('shows an error banner when upload fails', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;
    const file = new File(['hello'], 'notes.txt');
    component.onFileSelected(source.id, { target: { files: [file] } } as unknown as Event);

    component.upload(source.id);

    httpMock
      .expectOne(`${environment.apiBaseUrl}/knowledge-sources/${source.id}/documents`)
      .flush({ message: 'RAG features need an active OpenAI, Gemini, or Local credential -- PUT /vendor-credentials first.' }, { status: 400, statusText: 'Bad Request' });

    expect(component.uploadMessages[source.id].kind).toBe('error');
    expect(component.uploadMessages[source.id].text).toContain('vendor-credentials');
  });

  it('does not upload when no file has been selected', () => {
    const fixture = createComponent();
    fixture.componentInstance.upload(source.id);

    httpMock.expectNone(`${environment.apiBaseUrl}/knowledge-sources/${source.id}/documents`);
  });

  it('runs a test query and stores the ranked results', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;
    component.queryText[source.id] = 'how does leader election work';

    component.runQuery(source.id);

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/knowledge-sources/${source.id}/query`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ query: 'how does leader election work', topK: null });
    req.flush([{ chunkId: 'c1', documentName: 'notes.txt', content: 'etcd leases...', score: 0.91 }]);

    expect(component.queryResults[source.id]).toHaveLength(1);
    expect(component.queryResults[source.id][0].content).toBe('etcd leases...');
  });

  it('shows an error banner when the query fails', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;
    component.queryText[source.id] = 'anything';

    component.runQuery(source.id);

    httpMock
      .expectOne(`${environment.apiBaseUrl}/knowledge-sources/${source.id}/query`)
      .flush({ message: 'Query failed' }, { status: 500, statusText: 'Internal Server Error' });

    expect(component.queryError[source.id]).toBe('Query failed');
  });

  it('toggleQuery opens and closes the test-query panel for one source at a time', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;

    component.toggleQuery(source.id);
    expect(component.queryOpenId()).toBe(source.id);

    component.toggleQuery(source.id);
    expect(component.queryOpenId()).toBeNull();
  });
});
