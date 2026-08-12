import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { CredentialService } from './credential.service';
import { environment } from '../../../environments/environment';

describe('CredentialService', () => {
  let service: CredentialService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CredentialService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('listVendorCredentials() GETs /vendor-credentials', () => {
    service.listVendorCredentials().subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('putVendorCredential() PUTs {provider, token}', () => {
    service.putVendorCredential({ provider: 'ANTHROPIC', token: 'sk-abc' }).subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ provider: 'ANTHROPIC', token: 'sk-abc' });
    req.flush({});
  });

  it('deleteVendorCredential() DELETEs /vendor-credentials/{provider}', () => {
    service.deleteVendorCredential('ANTHROPIC').subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials/ANTHROPIC`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('testVendorCredential() POSTs {provider} to /vendor-credentials/test', () => {
    service.testVendorCredential('ANTHROPIC').subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials/test`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ provider: 'ANTHROPIC' });
    req.flush({ valid: true, message: 'ok' });
  });

  it('listToolCredentials() GETs /tool-credentials', () => {
    service.listToolCredentials().subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/tool-credentials`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('putToolCredential() PUTs {credentialKind, value}', () => {
    service.putToolCredential({ credentialKind: 'GITHUB', value: 'ghp_abc' }).subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/tool-credentials`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ credentialKind: 'GITHUB', value: 'ghp_abc' });
    req.flush({});
  });

  it('deleteToolCredential() DELETEs /tool-credentials/{credentialKind}', () => {
    service.deleteToolCredential('GITHUB').subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/tool-credentials/GITHUB`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('testToolCredential() POSTs {credentialKind} to /tool-credentials/test', () => {
    service.testToolCredential('GITHUB').subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/tool-credentials/test`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ credentialKind: 'GITHUB' });
    req.flush({ valid: true, message: 'ok' });
  });
});
