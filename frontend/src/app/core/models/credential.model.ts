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

export interface TeamVendorCredentialSummary {
  userId: string;
  userEmail: string;
  provider: string;
  active: boolean;
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
  preferredModelName: string | null;
  /** Null means "no override -- use the server-wide default token budget per agent execution". */
  maxTokensPerExecution: number | null;
  /** Never null -- whatever maxTokensPerExecution actually resolves to (this override, or the server default). */
  effectiveMaxTokensPerExecution: number;
  /** Null means no spend ceiling (unlimited) -- distinct from 0, which means "spend nothing". */
  monthlyBudgetUsd: number | null;
  availableProviders: LlmProviderAvailability[];
}

export interface ModelOption {
  id: string;
  label: string;
}
