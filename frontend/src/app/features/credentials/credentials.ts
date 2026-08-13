import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CredentialService } from '../../core/services/credential.service';
import { TenantSettingsService } from '../../core/services/tenant-settings.service';
import { LlmProviderAvailability, ModelOption, ToolCredentialSummary, VendorCredentialSummary } from '../../core/models/credential.model';
import { LocalDateTimePipe } from '../../shared/pipes/local-date-time.pipe';

interface VendorProviderDef {
  provider: string;
  label: string;
  testSupported: boolean;
}

interface ToolKindDef {
  credentialKind: string;
  label: string;
  testSupported: boolean;
}

interface RowMessage {
  kind: 'success' | 'error';
  text: string;
}

export const VENDOR_PROVIDERS: VendorProviderDef[] = [
  { provider: 'ANTHROPIC', label: 'Anthropic', testSupported: true },
  { provider: 'OPENAI', label: 'OpenAI', testSupported: true },
  { provider: 'GEMINI', label: 'Gemini', testSupported: true },
  { provider: 'LOCAL', label: 'Local (Ollama, LM Studio, vLLM)', testSupported: true },
];

export const TOOL_KINDS: ToolKindDef[] = [
  { credentialKind: 'GITHUB', label: 'GitHub', testSupported: true },
  { credentialKind: 'GIT', label: 'Git (generic)', testSupported: false },
];

/** GIT (used by git_clone) and GITHUB (used by open_pull_request) are usually the
 *  same personal access token in practice -- pairing them lets the UI offer to
 *  save one value under both kinds instead of making the user paste it twice. */
const PAIRED_TOOL_KIND: Record<string, string> = { GIT: 'GITHUB', GITHUB: 'GIT' };

@Component({
  selector: 'app-credentials',
  imports: [FormsModule, LocalDateTimePipe],
  templateUrl: './credentials.html',
  styleUrl: './credentials.css',
})
export class Credentials implements OnInit {
  private readonly credentialService = inject(CredentialService);
  private readonly tenantSettingsService = inject(TenantSettingsService);

  readonly vendorProviders = VENDOR_PROVIDERS;
  readonly toolKinds = TOOL_KINDS;

  readonly vendorCredentials = signal<VendorCredentialSummary[]>([]);
  readonly toolCredentials = signal<ToolCredentialSummary[]>([]);
  readonly loading = signal(true);
  readonly loadError = signal<string | null>(null);

  /** '' means "no override -- use the server default", matching a null preferredLlmProvider. */
  readonly preferredProviderSelection = signal('');
  /** '' means "no override -- use the provider's default model". */
  readonly preferredModelSelection = signal('');
  readonly availableProviders = signal<LlmProviderAvailability[]>([]);
  readonly savingPreference = signal(false);
  readonly preferenceMessage = signal<RowMessage | null>(null);

  readonly modelOptions = signal<ModelOption[]>([]);
  readonly loadingModels = signal(false);
  readonly modelsError = signal<string | null>(null);

  readonly vendorInputs: Record<string, string> = {};
  readonly toolInputs: Record<string, string> = {};
  readonly reuseForPair: Record<string, boolean> = {};

  readonly savingKey = signal<string | null>(null);
  readonly testingKey = signal<string | null>(null);
  readonly removingKey = signal<string | null>(null);

  readonly vendorMessages: Record<string, RowMessage> = {};
  readonly toolMessages: Record<string, RowMessage> = {};

  ngOnInit(): void {
    this.loading.set(true);
    this.loadError.set(null);
    let pending = 3;
    const done = () => {
      pending -= 1;
      if (pending === 0) {
        this.loading.set(false);
      }
    };
    this.credentialService.listVendorCredentials().subscribe({
      next: (list) => {
        this.vendorCredentials.set(list);
        done();
      },
      error: (err) => {
        this.loadError.set(this.extractMessage(err, 'Failed to load vendor credentials.'));
        done();
      },
    });
    this.credentialService.listToolCredentials().subscribe({
      next: (list) => {
        this.toolCredentials.set(list);
        done();
      },
      error: (err) => {
        this.loadError.set(this.extractMessage(err, 'Failed to load tool credentials.'));
        done();
      },
    });
    this.tenantSettingsService.getSettings().subscribe({
      next: (settings) => {
        this.preferredProviderSelection.set(settings.preferredLlmProvider ?? '');
        this.preferredModelSelection.set(settings.preferredModelName ?? '');
        this.availableProviders.set(settings.availableProviders);
        done();
      },
      error: (err) => {
        this.loadError.set(this.extractMessage(err, 'Failed to load LLM provider preference.'));
        done();
      },
    });
  }

  private refreshVendorCredentials(): void {
    this.credentialService.listVendorCredentials().subscribe((list) => this.vendorCredentials.set(list));
  }

  private refreshToolCredentials(): void {
    this.credentialService.listToolCredentials().subscribe((list) => this.toolCredentials.set(list));
  }

  private extractMessage(err: any, fallback: string): string {
    return err?.error?.message ?? fallback;
  }

  vendorSummary(provider: string): VendorCredentialSummary | undefined {
    return this.vendorCredentials().find((c) => c.provider === provider);
  }

  /** A provider is selectable as the preference only once it has a real, active credential behind it. */
  hasActiveCredential(provider: string): boolean {
    return this.availableProviders().find((p) => p.provider === provider)?.hasActiveCredential ?? false;
  }

  modelIsInOptions(modelId: string): boolean {
    return this.modelOptions().some((option) => option.id === modelId);
  }

  /** Whichever models were loaded for the currently-selected provider might not include a model name the tenant already had saved -- shown anyway so the dropdown never silently drops the current selection. */
  onProviderSelectionChange(): void {
    this.modelOptions.set([]);
    this.modelsError.set(null);
  }

  loadModels(): void {
    const provider = this.preferredProviderSelection();
    if (!provider) {
      return;
    }
    this.loadingModels.set(true);
    this.modelsError.set(null);
    this.credentialService.listModels(provider).subscribe({
      next: (options) => {
        this.loadingModels.set(false);
        this.modelOptions.set(options);
      },
      error: (err) => {
        this.loadingModels.set(false);
        this.modelsError.set(this.extractMessage(err, 'Failed to load models.'));
      },
    });
  }

  savePreferredProvider(): void {
    const provider = this.preferredProviderSelection();
    const modelName = this.preferredModelSelection();
    this.savingPreference.set(true);
    this.preferenceMessage.set(null);
    this.tenantSettingsService.updateSettings(provider || null, modelName || null).subscribe({
      next: (settings) => {
        this.savingPreference.set(false);
        this.preferredProviderSelection.set(settings.preferredLlmProvider ?? '');
        this.preferredModelSelection.set(settings.preferredModelName ?? '');
        this.availableProviders.set(settings.availableProviders);
        this.preferenceMessage.set({ kind: 'success', text: 'Preference saved.' });
      },
      error: (err) => {
        this.savingPreference.set(false);
        this.preferenceMessage.set({ kind: 'error', text: this.extractMessage(err, 'Failed to save preference.') });
      },
    });
  }

  toolSummary(credentialKind: string): ToolCredentialSummary | undefined {
    return this.toolCredentials().find((c) => c.credentialKind === credentialKind);
  }

  saveVendorCredential(provider: string): void {
    const token = this.vendorInputs[provider];
    if (!token) {
      return;
    }
    this.putVendorCredential(provider, token, 'Key saved.');
  }

  /** LOCAL (Ollama/LM Studio/vLLM) has no real secret -- the credential row only exists to
   *  gate provider selection and model listing the same way every other provider does (see
   *  LlmEngineFactory), so there's nothing for the user to actually type in. This saves a
   *  fixed placeholder value directly instead of showing a key input for it. */
  connectLocal(): void {
    this.putVendorCredential('LOCAL', 'not-needed', 'Connected.');
  }

  private putVendorCredential(provider: string, token: string, successMessage: string): void {
    this.savingKey.set(provider);
    delete this.vendorMessages[provider];
    this.credentialService.putVendorCredential({ provider, token }).subscribe({
      next: () => {
        this.savingKey.set(null);
        this.vendorInputs[provider] = '';
        this.vendorMessages[provider] = { kind: 'success', text: successMessage };
        this.refreshVendorCredentials();
      },
      error: (err) => {
        this.savingKey.set(null);
        this.vendorMessages[provider] = { kind: 'error', text: this.extractMessage(err, 'Failed to save key.') };
      },
    });
  }

  pairedToolKindLabel(credentialKind: string): string | undefined {
    const pairedKind = PAIRED_TOOL_KIND[credentialKind];
    return pairedKind ? this.toolKinds.find((k) => k.credentialKind === pairedKind)?.label : undefined;
  }

  saveToolCredential(credentialKind: string): void {
    const value = this.toolInputs[credentialKind];
    if (!value) {
      return;
    }
    const alsoSaveAs = this.reuseForPair[credentialKind] ? PAIRED_TOOL_KIND[credentialKind] : undefined;
    this.savingKey.set(credentialKind);
    delete this.toolMessages[credentialKind];
    this.credentialService.putToolCredential({ credentialKind, value }).subscribe({
      next: () => {
        this.toolInputs[credentialKind] = '';
        this.reuseForPair[credentialKind] = false;
        this.toolMessages[credentialKind] = { kind: 'success', text: 'Value saved.' };
        this.refreshToolCredentials();
        if (!alsoSaveAs) {
          this.savingKey.set(null);
          return;
        }
        this.credentialService.putToolCredential({ credentialKind: alsoSaveAs, value }).subscribe({
          next: () => {
            this.savingKey.set(null);
            this.toolMessages[alsoSaveAs] = { kind: 'success', text: 'Value saved.' };
            this.refreshToolCredentials();
          },
          error: (err) => {
            this.savingKey.set(null);
            this.toolMessages[alsoSaveAs] = { kind: 'error', text: this.extractMessage(err, 'Failed to save value.') };
          },
        });
      },
      error: (err) => {
        this.savingKey.set(null);
        this.toolMessages[credentialKind] = { kind: 'error', text: this.extractMessage(err, 'Failed to save value.') };
      },
    });
  }

  removeVendorCredential(provider: string): void {
    this.removingKey.set(provider);
    delete this.vendorMessages[provider];
    this.credentialService.deleteVendorCredential(provider).subscribe({
      next: () => {
        this.removingKey.set(null);
        this.vendorMessages[provider] = { kind: 'success', text: 'Credential removed.' };
        this.refreshVendorCredentials();
      },
      error: (err) => {
        this.removingKey.set(null);
        this.vendorMessages[provider] = { kind: 'error', text: this.extractMessage(err, 'Failed to remove credential.') };
      },
    });
  }

  removeToolCredential(credentialKind: string): void {
    this.removingKey.set(credentialKind);
    delete this.toolMessages[credentialKind];
    this.credentialService.deleteToolCredential(credentialKind).subscribe({
      next: () => {
        this.removingKey.set(null);
        this.toolMessages[credentialKind] = { kind: 'success', text: 'Credential removed.' };
        this.refreshToolCredentials();
      },
      error: (err) => {
        this.removingKey.set(null);
        this.toolMessages[credentialKind] = { kind: 'error', text: this.extractMessage(err, 'Failed to remove credential.') };
      },
    });
  }

  testVendorCredential(provider: string): void {
    this.testingKey.set(provider);
    delete this.vendorMessages[provider];
    this.credentialService.testVendorCredential(provider).subscribe({
      next: (result) => {
        this.testingKey.set(null);
        this.vendorMessages[provider] = { kind: result.valid ? 'success' : 'error', text: result.message };
        this.refreshVendorCredentials();
      },
      error: (err) => {
        this.testingKey.set(null);
        this.vendorMessages[provider] = { kind: 'error', text: this.extractMessage(err, 'Test connection failed.') };
      },
    });
  }

  testToolCredential(credentialKind: string): void {
    this.testingKey.set(credentialKind);
    delete this.toolMessages[credentialKind];
    this.credentialService.testToolCredential(credentialKind).subscribe({
      next: (result) => {
        this.testingKey.set(null);
        this.toolMessages[credentialKind] = { kind: result.valid ? 'success' : 'error', text: result.message };
        this.refreshToolCredentials();
      },
      error: (err) => {
        this.testingKey.set(null);
        this.toolMessages[credentialKind] = { kind: 'error', text: this.extractMessage(err, 'Test connection failed.') };
      },
    });
  }
}
