import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Credentials } from './credentials';
import { environment } from '../../../environments/environment';
import { VendorCredentialSummary } from '../../core/models/credential.model';

describe('Credentials', () => {
  let httpMock: HttpTestingController;

  const activeAnthropic: VendorCredentialSummary = {
    id: 'vc-1',
    provider: 'ANTHROPIC',
    active: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    lastUsedAt: null,
    lastValidatedAt: null,
  };

  function createComponent() {
    const fixture = TestBed.createComponent(Credentials);
    fixture.detectChanges();
    httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials`).flush([]);
    httpMock.expectOne(`${environment.apiBaseUrl}/tool-credentials`).flush([]);
    return fixture;
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Credentials],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('loads vendor and tool credentials on init', () => {
    createComponent();
  });

  it('saves a vendor credential and refreshes the list', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;
    component.vendorInputs['ANTHROPIC'] = 'sk-new-key';

    component.saveVendorCredential('ANTHROPIC');

    const putReq = httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials`);
    expect(putReq.request.method).toBe('PUT');
    expect(putReq.request.body).toEqual({ provider: 'ANTHROPIC', token: 'sk-new-key' });
    putReq.flush(activeAnthropic);

    httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials`).flush([activeAnthropic]);

    expect(component.vendorSummary('ANTHROPIC')?.active).toBe(true);
    expect(component.vendorInputs['ANTHROPIC']).toBe('');
  });

  it('does not save when the input is empty', () => {
    const fixture = createComponent();
    fixture.componentInstance.saveVendorCredential('ANTHROPIC');
    httpMock.expectNone(`${environment.apiBaseUrl}/vendor-credentials`);
  });

  it('tests a vendor credential and stores the result', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;

    component.testVendorCredential('ANTHROPIC');

    const testReq = httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials/test`);
    expect(testReq.request.method).toBe('POST');
    expect(testReq.request.body).toEqual({ provider: 'ANTHROPIC' });
    testReq.flush({ valid: true, message: 'Anthropic credential is valid.' });

    httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials`).flush([activeAnthropic]);

    expect(component.testResults['ANTHROPIC']).toEqual({ valid: true, message: 'Anthropic credential is valid.' });
  });

  it('removes a tool credential and refreshes the list', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;

    component.removeToolCredential('GITHUB');

    httpMock.expectOne(`${environment.apiBaseUrl}/tool-credentials/GITHUB`).flush(null);
    httpMock.expectOne(`${environment.apiBaseUrl}/tool-credentials`).flush([]);

    expect(component.toolSummary('GITHUB')).toBeUndefined();
  });
});
