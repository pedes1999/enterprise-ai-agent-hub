import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Credentials } from './credentials';
import { AuthService } from '../../core/services/auth.service';
import { environment } from '../../../environments/environment';
import { ModelOption, TeamVendorCredentialSummary, TenantSettings, VendorCredentialSummary } from '../../core/models/credential.model';

describe('Credentials', () => {
  let httpMock: HttpTestingController;
  // Most tests below exercise the full admin page (agent defaults, tool
  // credentials, team view) -- only fetched when isAdmin() is true (see
  // ngOnInit's javadoc comment: those endpoints are still ADMIN-only
  // server-side). The DEVELOPER-specific behavior gets its own describe
  // block further down, toggling this to false.
  let isAdmin = true;

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
    preferredModelName: null,
    maxTokensPerExecution: null,
    effectiveMaxTokensPerExecution: 500000,
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
    httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials/team`).flush([]);
    return fixture;
  }

  beforeEach(async () => {
    isAdmin = true;
    await TestBed.configureTestingModule({
      imports: [Credentials],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { isAdmin: () => isAdmin } },
      ],
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

  it('connectLocal() saves a fixed placeholder token without requiring any input', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;

    component.connectLocal();

    const putReq = httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials`);
    expect(putReq.request.method).toBe('PUT');
    expect(putReq.request.body).toEqual({ provider: 'LOCAL', token: 'not-needed' });
    putReq.flush({ ...activeAnthropic, provider: 'LOCAL' });

    httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials`).flush([{ ...activeAnthropic, provider: 'LOCAL' }]);

    expect(component.vendorSummary('LOCAL')?.active).toBe(true);
    expect(component.vendorMessages['LOCAL']).toEqual({ kind: 'success', text: 'Connected.' });
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
    httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials/team`).flush([]);

    expect(fixture.componentInstance.loadError()).toBe('Access denied');
    expect(fixture.componentInstance.loading()).toBe(false);
  });

  it('loads the preferred provider, model, max tokens, and available providers on init', () => {
    const fixture = createComponent({
      preferredLlmProvider: 'LOCAL',
      preferredModelName: 'llama3.1',
      maxTokensPerExecution: 75000,
      effectiveMaxTokensPerExecution: 75000,
      availableProviders: [
        { provider: 'ANTHROPIC', hasActiveCredential: true },
        { provider: 'LOCAL', hasActiveCredential: true },
      ],
    });
    const component = fixture.componentInstance;

    expect(component.preferredProviderSelection()).toBe('LOCAL');
    expect(component.preferredModelSelection()).toBe('llama3.1');
    expect(component.maxTokensSelection()).toBe('75000');
    expect(component.effectiveMaxTokens()).toBe(75000);
    expect(component.hasActiveCredential('ANTHROPIC')).toBe(true);
    expect(component.hasActiveCredential('OPENAI')).toBe(false);
  });

  it('isDirty is false right after load, true once a field changes, false again after a successful save', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;
    expect(component.isDirty()).toBe(false);

    component.maxTokensSelection.set('50000');
    expect(component.isDirty()).toBe(true);

    component.savePreferredProvider();
    httpMock.expectOne(`${environment.apiBaseUrl}/tenant-settings`).flush({
      preferredLlmProvider: null,
      preferredModelName: null,
      maxTokensPerExecution: 50000,
      effectiveMaxTokensPerExecution: 50000,
      availableProviders: [],
    });

    expect(component.isDirty()).toBe(false);
  });

  it('saves the preferred provider, model, and max tokens, reflecting the server response', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;
    component.preferredProviderSelection.set('LOCAL');
    component.preferredModelSelection.set('llama3.1');
    component.maxTokensSelection.set('75000');

    component.savePreferredProvider();

    const putReq = httpMock.expectOne(`${environment.apiBaseUrl}/tenant-settings`);
    expect(putReq.request.method).toBe('PUT');
    expect(putReq.request.body).toEqual({ preferredLlmProvider: 'LOCAL', preferredModelName: 'llama3.1', maxTokensPerExecution: 75000 });
    putReq.flush({
      preferredLlmProvider: 'LOCAL',
      preferredModelName: 'llama3.1',
      maxTokensPerExecution: 75000,
      effectiveMaxTokensPerExecution: 75000,
      availableProviders: [{ provider: 'LOCAL', hasActiveCredential: true }],
    });

    expect(component.preferenceMessage()).toEqual({ kind: 'success', text: 'Preference saved.' });
    expect(component.savingPreference()).toBe(false);
  });

  it('sends null when clearing the preference back to the server default', () => {
    const fixture = createComponent({
      preferredLlmProvider: 'LOCAL',
      preferredModelName: 'llama3.1',
      maxTokensPerExecution: 75000,
      effectiveMaxTokensPerExecution: 75000,
      availableProviders: [{ provider: 'LOCAL', hasActiveCredential: true }],
    });
    const component = fixture.componentInstance;
    component.preferredProviderSelection.set('');
    component.preferredModelSelection.set('');
    component.maxTokensSelection.set('');

    component.savePreferredProvider();

    const putReq = httpMock.expectOne(`${environment.apiBaseUrl}/tenant-settings`);
    expect(putReq.request.body).toEqual({ preferredLlmProvider: null, preferredModelName: null, maxTokensPerExecution: null });
    putReq.flush({
      preferredLlmProvider: null,
      preferredModelName: null,
      maxTokensPerExecution: null,
      effectiveMaxTokensPerExecution: 500000,
      availableProviders: [],
    });
  });

  it('rejects a non-positive max tokens value without calling the server', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;
    component.maxTokensSelection.set('0');

    component.savePreferredProvider();

    httpMock.expectNone(`${environment.apiBaseUrl}/tenant-settings`);
    expect(component.preferenceMessage()?.kind).toBe('error');
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

  it('loads models for the selected provider', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;
    component.preferredProviderSelection.set('ANTHROPIC');

    component.loadModels();

    const req = httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials/ANTHROPIC/models`);
    expect(req.request.method).toBe('GET');
    const options: ModelOption[] = [{ id: 'claude-opus-4-1-20250805', label: 'Claude Opus 4.1' }];
    req.flush(options);

    expect(component.modelOptions()).toEqual(options);
    expect(component.loadingModels()).toBe(false);
  });

  it('does not load models when no provider is selected', () => {
    const fixture = createComponent();
    fixture.componentInstance.loadModels();
    httpMock.expectNone((req) => req.url.includes('/models'));
  });

  it('shows an error when loading models fails', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;
    component.preferredProviderSelection.set('LOCAL');

    component.loadModels();

    httpMock
      .expectOne(`${environment.apiBaseUrl}/vendor-credentials/LOCAL/models`)
      .flush({ message: 'Local model list request failed: connection refused' }, { status: 502, statusText: 'Bad Gateway' });

    expect(component.modelsError()).toBe('Local model list request failed: connection refused');
    expect(component.loadingModels()).toBe(false);
  });

  it('clears stale model options when the provider selection changes', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;
    component.modelOptions.set([{ id: 'gpt-4o', label: 'gpt-4o' }]);
    component.modelsError.set('some previous error');

    component.onProviderSelectionChange();

    expect(component.modelOptions()).toEqual([]);
    expect(component.modelsError()).toBeNull();
  });

  it('modelIsInOptions reflects whether the loaded list contains the given id', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;
    component.modelOptions.set([{ id: 'gpt-4o', label: 'gpt-4o' }]);

    expect(component.modelIsInOptions('gpt-4o')).toBe(true);
    expect(component.modelIsInOptions('gpt-3.5')).toBe(false);
  });

  describe('team credentials (ADMIN only)', () => {
    const teamRow: TeamVendorCredentialSummary = {
      userId: 'user-2',
      userEmail: 'dev@acme.com',
      provider: 'OPENAI',
      active: true,
      lastUsedAt: null,
      lastValidatedAt: null,
    };

    it('loads the team list for an admin', () => {
      const fixture = TestBed.createComponent(Credentials);
      fixture.detectChanges();
      httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials`).flush([]);
      httpMock.expectOne(`${environment.apiBaseUrl}/tool-credentials`).flush([]);
      httpMock.expectOne(`${environment.apiBaseUrl}/tenant-settings`).flush(noPreference);
      httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials/team`).flush([teamRow]);

      expect(fixture.componentInstance.teamCredentials()).toEqual([teamRow]);
    });

    it('deactivates a teammate credential and refreshes the team list', () => {
      isAdmin = true;
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      const fixture = TestBed.createComponent(Credentials);
      fixture.detectChanges();
      httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials`).flush([]);
      httpMock.expectOne(`${environment.apiBaseUrl}/tool-credentials`).flush([]);
      httpMock.expectOne(`${environment.apiBaseUrl}/tenant-settings`).flush(noPreference);
      httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials/team`).flush([teamRow]);

      fixture.componentInstance.deactivateTeamCredential(teamRow);

      const deactivateReq = httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials/team/user-2/OPENAI/deactivate`);
      expect(deactivateReq.request.method).toBe('POST');
      deactivateReq.flush(null);

      httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials/team`).flush([{ ...teamRow, active: false }]);

      expect(fixture.componentInstance.teamCredentials()[0].active).toBe(false);
      expect(fixture.componentInstance.deactivatingKey()).toBeNull();
    });

    it('skips the deactivate request when the confirmation is declined', () => {
      isAdmin = true;
      vi.spyOn(window, 'confirm').mockReturnValue(false);
      const fixture = TestBed.createComponent(Credentials);
      fixture.detectChanges();
      httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials`).flush([]);
      httpMock.expectOne(`${environment.apiBaseUrl}/tool-credentials`).flush([]);
      httpMock.expectOne(`${environment.apiBaseUrl}/tenant-settings`).flush(noPreference);
      httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials/team`).flush([teamRow]);

      fixture.componentInstance.deactivateTeamCredential(teamRow);

      httpMock.expectNone(`${environment.apiBaseUrl}/vendor-credentials/team/user-2/OPENAI/deactivate`);
    });
  });

  describe('as a DEVELOPER (non-admin)', () => {
    beforeEach(() => {
      isAdmin = false;
    });

    it('only requests its own vendor credentials -- tool credentials, agent defaults, and team are ADMIN-only and never called', () => {
      const fixture = TestBed.createComponent(Credentials);
      fixture.detectChanges();
      httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials`).flush([activeAnthropic]);

      httpMock.expectNone(`${environment.apiBaseUrl}/tool-credentials`);
      httpMock.expectNone(`${environment.apiBaseUrl}/tenant-settings`);
      httpMock.expectNone(`${environment.apiBaseUrl}/vendor-credentials/team`);
      expect(fixture.componentInstance.loadError()).toBeNull();
      expect(fixture.componentInstance.loading()).toBe(false);
      expect(fixture.componentInstance.vendorSummary('ANTHROPIC')?.active).toBe(true);
    });

    it('can still save its own vendor credential', () => {
      const fixture = TestBed.createComponent(Credentials);
      fixture.detectChanges();
      httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials`).flush([]);
      const component = fixture.componentInstance;
      component.vendorInputs['ANTHROPIC'] = 'sk-new-key';

      component.saveVendorCredential('ANTHROPIC');

      const putReq = httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials`);
      putReq.flush(activeAnthropic);
      httpMock.expectOne(`${environment.apiBaseUrl}/vendor-credentials`).flush([activeAnthropic]);

      expect(component.vendorSummary('ANTHROPIC')?.active).toBe(true);
    });
  });
});
