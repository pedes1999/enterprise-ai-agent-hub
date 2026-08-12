import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Credentials } from './credentials';
import { environment } from '../../../environments/environment';
import { TenantSettings, VendorCredentialSummary } from '../../core/models/credential.model';

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

  const noPreference: TenantSettings = {
    preferredLlmProvider: null,
    availableProviders: [
      { provider: 'ANTHROPIC', hasActiveCredential: true },
      { provider: 'OPENAI', hasActiveCredential: false },
      { provider: 'GEMINI', hasActiveCredential: false },
      { provider: 'LOCAL', hasActiveCredential: false },
    ],
  };

  function createComponent(settings: TenantSettings = noPreference) {
    const fixture = TestBed.createComponent(Credentials);
    fixture.detectChanges();
    httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials`).flush([]);
    httpMock.expectOne(`${environment.apiBaseUrl}/tool-credentials`).flush([]);
    httpMock.expectOne(`${environment.apiBaseUrl}/tenant-settings`).flush(settings);
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

  it('tests a vendor credential and stores a success message', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;

    component.testVendorCredential('ANTHROPIC');

    const testReq = httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials/test`);
    expect(testReq.request.method).toBe('POST');
    expect(testReq.request.body).toEqual({ provider: 'ANTHROPIC' });
    testReq.flush({ valid: true, message: 'Anthropic credential is valid.' });

    httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials`).flush([activeAnthropic]);

    expect(component.vendorMessages['ANTHROPIC']).toEqual({ kind: 'success', text: 'Anthropic credential is valid.' });
  });

  it('records a failed test-connection result as an error message', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;

    component.testVendorCredential('ANTHROPIC');

    httpMock
      .expectOne(`${environment.apiBaseUrl}/vendor-credentials/test`)
      .flush({ valid: false, message: 'Anthropic rejected this credential.' });
    httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials`).flush([activeAnthropic]);

    expect(component.vendorMessages['ANTHROPIC']).toEqual({ kind: 'error', text: 'Anthropic rejected this credential.' });
  });

  it('shows an error message when a test-connection request itself fails', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;

    component.testVendorCredential('ANTHROPIC');

    httpMock
      .expectOne(`${environment.apiBaseUrl}/vendor-credentials/test`)
      .flush({ message: 'No active credential stored for provider ANTHROPIC' }, { status: 404, statusText: 'Not Found' });

    expect(component.vendorMessages['ANTHROPIC']).toEqual({
      kind: 'error',
      text: 'No active credential stored for provider ANTHROPIC',
    });
    expect(component.testingKey()).toBeNull();
  });

  it('removes a tool credential, shows a success message, and refreshes the list', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;

    component.removeToolCredential('GITHUB');

    httpMock.expectOne(`${environment.apiBaseUrl}/tool-credentials/GITHUB`).flush(null);
    httpMock.expectOne(`${environment.apiBaseUrl}/tool-credentials`).flush([]);

    expect(component.toolSummary('GITHUB')).toBeUndefined();
    expect(component.toolMessages['GITHUB']).toEqual({ kind: 'success', text: 'Credential removed.' });
  });

  it('saves a tool credential without touching the paired kind by default', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;
    component.toolInputs['GIT'] = 'ghp_token';

    component.saveToolCredential('GIT');

    httpMock.expectOne(`${environment.apiBaseUrl}/tool-credentials`).flush(null);
    httpMock.expectOne(`${environment.apiBaseUrl}/tool-credentials`).flush([]);

    expect(component.toolMessages['GIT']).toEqual({ kind: 'success', text: 'Value saved.' });
    expect(component.savingKey()).toBeNull();
  });

  it('also saves the paired tool credential when reuseForPair is checked', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;
    component.toolInputs['GIT'] = 'ghp_token';
    component.reuseForPair['GIT'] = true;

    component.saveToolCredential('GIT');

    const putReqs = httpMock.match(`${environment.apiBaseUrl}/tool-credentials`);
    const gitPut = putReqs.find((r) => r.request.method === 'PUT' && r.request.body.credentialKind === 'GIT');
    expect(gitPut?.request.body).toEqual({ credentialKind: 'GIT', value: 'ghp_token' });
    gitPut!.flush(null);

    const afterGitReqs = httpMock.match(`${environment.apiBaseUrl}/tool-credentials`);
    const firstRefresh = afterGitReqs.find((r) => r.request.method === 'GET');
    const githubPut = afterGitReqs.find((r) => r.request.method === 'PUT' && r.request.body.credentialKind === 'GITHUB');
    firstRefresh!.flush([]);
    expect(githubPut?.request.body).toEqual({ credentialKind: 'GITHUB', value: 'ghp_token' });
    githubPut!.flush(null);

    httpMock.expectOne((req) => req.method === 'GET' && req.url === `${environment.apiBaseUrl}/tool-credentials`).flush([]);

    expect(component.toolMessages['GIT']).toEqual({ kind: 'success', text: 'Value saved.' });
    expect(component.toolMessages['GITHUB']).toEqual({ kind: 'success', text: 'Value saved.' });
    expect(component.reuseForPair['GIT']).toBe(false);
    expect(component.savingKey()).toBeNull();
  });

  it('pairedToolKindLabel returns the paired label for GIT/GITHUB and undefined otherwise', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;

    expect(component.pairedToolKindLabel('GIT')).toBe('GitHub');
    expect(component.pairedToolKindLabel('GITHUB')).toBe('Git (generic)');
  });

  it('shows a top-level error banner when the initial load fails', () => {
    const fixture = TestBed.createComponent(Credentials);
    fixture.detectChanges();

    httpMock
      .expectOne(`${environment.apiBaseUrl}/vendor-credentials`)
      .flush({ message: 'Access denied' }, { status: 403, statusText: 'Forbidden' });
    httpMock.expectOne(`${environment.apiBaseUrl}/tool-credentials`).flush([]);
    httpMock.expectOne(`${environment.apiBaseUrl}/tenant-settings`).flush([]);

    expect(fixture.componentInstance.loadError()).toBe('Access denied');
    expect(fixture.componentInstance.loading()).toBe(false);
  });

  it('loads the preferred provider and available providers on init', () => {
    const fixture = createComponent({
      preferredLlmProvider: 'LOCAL',
      availableProviders: [
        { provider: 'ANTHROPIC', hasActiveCredential: true },
        { provider: 'LOCAL', hasActiveCredential: true },
      ],
    });
    const component = fixture.componentInstance;

    expect(component.preferredProviderSelection()).toBe('LOCAL');
    expect(component.hasActiveCredential('ANTHROPIC')).toBe(true);
    expect(component.hasActiveCredential('OPENAI')).toBe(false);
  });

  it('saves the preferred provider and reflects the server response', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;
    component.preferredProviderSelection.set('LOCAL');

    component.savePreferredProvider();

    const putReq = httpMock.expectOne(`${environment.apiBaseUrl}/tenant-settings`);
    expect(putReq.request.method).toBe('PUT');
    expect(putReq.request.body).toEqual({ preferredLlmProvider: 'LOCAL' });
    putReq.flush({
      preferredLlmProvider: 'LOCAL',
      availableProviders: [{ provider: 'LOCAL', hasActiveCredential: true }],
    });

    expect(component.preferenceMessage()).toEqual({ kind: 'success', text: 'Preference saved.' });
    expect(component.savingPreference()).toBe(false);
  });

  it('sends null when clearing the preference back to the server default', () => {
    const fixture = createComponent({
      preferredLlmProvider: 'LOCAL',
      availableProviders: [{ provider: 'LOCAL', hasActiveCredential: true }],
    });
    const component = fixture.componentInstance;
    component.preferredProviderSelection.set('');

    component.savePreferredProvider();

    const putReq = httpMock.expectOne(`${environment.apiBaseUrl}/tenant-settings`);
    expect(putReq.request.body).toEqual({ preferredLlmProvider: null });
    putReq.flush({ preferredLlmProvider: null, availableProviders: [] });
  });

  it('shows an error message when saving the preference fails', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;
    component.preferredProviderSelection.set('LOCAL');

    component.savePreferredProvider();

    httpMock
      .expectOne(`${environment.apiBaseUrl}/tenant-settings`)
      .flush({ message: 'No active LOCAL credential configured for this tenant -- PUT /vendor-credentials first' }, { status: 400, statusText: 'Bad Request' });

    expect(component.preferenceMessage()).toEqual({
      kind: 'error',
      text: 'No active LOCAL credential configured for this tenant -- PUT /vendor-credentials first',
    });
    expect(component.savingPreference()).toBe(false);
  });
});
