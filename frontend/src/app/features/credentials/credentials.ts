import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CredentialService } from '../../core/services/credential.service';
import { CredentialTestResult, ToolCredentialSummary, VendorCredentialSummary } from '../../core/models/credential.model';

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

  readonly vendorInputs: Record<string, string> = {};
  readonly toolInputs: Record<string, string> = {};

  readonly savingKey = signal<string | null>(null);
  readonly testingKey = signal<string | null>(null);
  readonly testResults: Record<string, CredentialTestResult> = {};

  ngOnInit(): void {
    this.refreshVendorCredentials();
    this.refreshToolCredentials();
  }

  private refreshVendorCredentials(): void {
    this.credentialService.listVendorCredentials().subscribe((list) => this.vendorCredentials.set(list));
  }

  private refreshToolCredentials(): void {
    this.credentialService.listToolCredentials().subscribe((list) => this.toolCredentials.set(list));
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
    this.credentialService.putVendorCredential({ provider, token }).subscribe({
      next: () => {
        this.savingKey.set(null);
        this.vendorInputs[provider] = '';
        this.refreshVendorCredentials();
      },
      error: () => this.savingKey.set(null),
    });
  }

  saveToolCredential(credentialKind: string): void {
    const value = this.toolInputs[credentialKind];
    if (!value) {
      return;
    }
    this.savingKey.set(credentialKind);
    this.credentialService.putToolCredential({ credentialKind, value }).subscribe({
      next: () => {
        this.savingKey.set(null);
        this.toolInputs[credentialKind] = '';
        this.refreshToolCredentials();
      },
      error: () => this.savingKey.set(null),
    });
  }

  removeVendorCredential(provider: string): void {
    this.credentialService.deleteVendorCredential(provider).subscribe(() => this.refreshVendorCredentials());
  }

  removeToolCredential(credentialKind: string): void {
    this.credentialService.deleteToolCredential(credentialKind).subscribe(() => this.refreshToolCredentials());
  }

  testVendorCredential(provider: string): void {
    this.testingKey.set(provider);
    this.credentialService.testVendorCredential(provider).subscribe({
      next: (result) => {
        this.testingKey.set(null);
        this.testResults[provider] = result;
        this.refreshVendorCredentials();
      },
      error: () => this.testingKey.set(null),
    });
  }

  testToolCredential(credentialKind: string): void {
    this.testingKey.set(credentialKind);
    this.credentialService.testToolCredential(credentialKind).subscribe({
      next: (result) => {
        this.testingKey.set(null);
        this.testResults[credentialKind] = result;
        this.refreshToolCredentials();
      },
      error: () => this.testingKey.set(null),
    });
  }
}
