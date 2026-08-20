import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { WebhookService } from './webhook.service';
import { environment } from '../../../environments/environment';

describe('WebhookService', () => {
  let service: WebhookService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(WebhookService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('list() GETs /webhook-endpoints', () => {
    service.list().subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/webhook-endpoints`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('create() POSTs {agentSlug, label, runAsUserId}', () => {
    service.create({ agentSlug: 'test-fixer', label: 'billing-service PRs', runAsUserId: null }).subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/webhook-endpoints`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ agentSlug: 'test-fixer', label: 'billing-service PRs', runAsUserId: null });
    req.flush({});
  });

  it('deactivate() DELETEs /webhook-endpoints/{id}', () => {
    service.deactivate('endpoint-1').subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/webhook-endpoints/endpoint-1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
