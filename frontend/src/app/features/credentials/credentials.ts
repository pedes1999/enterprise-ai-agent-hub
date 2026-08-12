import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CredentialService } from '../../core/services/credential.service';
import { ToolCredentialSummary, VendorCredentialSummary } from '../../core/models/credential.model';

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
  { provider: 'OPENAI', label: 'OpenAI', testSupported: false },
  { provider: 'GEMINI', label: 'Gemini', testSupported: false },
];

export const TOOL_KINDS: ToolKindDef[] = [
  { credentialKind: 'GITHUB', label: 'GitHub', testSupported: true },
  { credentialKind: 'GIT', label: 'Git (generic)', testSupported: false },
];

@Component({
  selector: 'app-credentials',
  imports: [FormsModule],
  templateUrl: './credentials.html',
  styleUrl: './credentials.css',
})
export class Credentials implements OnInit {
  private readonly credentialService = inject(CredentialService);

  readonly vendorProviders = VENDOR_PROVIDERS;
  readonly toolKinds = TOOL_KINDS;

  readonly vendorCredentials = signal<VendorCredentialSummary[]>([]);
  readonly toolCredentials = signal<ToolCredentialSummary[]>([]);
  readonly loading = signal(true);
  readonly loadError = signal<string | null>(null);

  readonly vendorInputs: Record<string, string> = {};
  readonly toolInputs: Record<string, string> = {};

  readonly savingKey = signal<string | null>(null);
  readonly testingKey = signal<string | null>(null);
  readonly removingKey = signal<string | null>(null);

  readonly vendorMessages: Record<string, RowMessage> = {};
  readonly toolMessages: Record<string, RowMessage> = {};

  ngOnInit(): void {
    this.loading.set(true);
    this.loadError.set(null);
    let pending = 2;
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

  toolSummary(credentialKind: string): ToolCredentialSummary | undefined {
    return this.toolCredentials().find((c) => c.credentialKind === credentialKind);
  }

  saveVendorCredential(provider: string): void {
    const token = this.vendorInputs[provider];
    if (!token) {
      return;
    }
    this.savingKey.set(provider);
    delete this.vendorMessages[provider];
    this.credentialService.putVendorCredential({ provider, token }).subscribe({
      next: () => {
        this.savingKey.set(null);
        this.vendorInputs[provider] = '';
        this.vendorMessages[provider] = { kind: 'success', text: 'Key saved.' };
        this.refreshVendorCredentials();
      },
      error: (err) => {
        this.savingKey.set(null);
        this.vendorMessages[provider] = { kind: 'error', text: this.extractMessage(err, 'Failed to save key.') };
      },
    });
  }

  saveToolCredential(credentialKind: string): void {
    const value = this.toolInputs[credentialKind];
    if (!value) {
      return;
    }
    this.savingKey.set(credentialKind);
    delete this.toolMessages[credentialKind];
    this.credentialService.putToolCredential({ credentialKind, value }).subscribe({
      next: () => {
        this.savingKey.set(null);
        this.toolInputs[credentialKind] = '';
        this.toolMessages[credentialKind] = { kind: 'success', text: 'Value saved.' };
        this.refreshToolCredentials();
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
