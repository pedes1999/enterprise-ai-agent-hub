export interface VendorCredentialSummary {
  id: string;
  provider: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
  lastUsedAt: string | null;
  lastValidatedAt: string | null;
}

export interface ToolCredentialSummary {
  id: string;
  credentialKind: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
  lastUsedAt: string | null;
  lastValidatedAt: string | null;
}

export interface CreateVendorCredentialRequest {
  provider: string;
  token: string;
}

export interface CreateToolCredentialRequest {
  credentialKind: string;
  value: string;
}

export interface CredentialTestResult {
  valid: boolean;
  message: string;
}

export interface LlmProviderAvailability {
  provider: string;
  hasActiveCredential: boolean;
}

export interface TenantSettings {
  preferredLlmProvider: string | null;
  availableProviders: LlmProviderAvailability[];
}
