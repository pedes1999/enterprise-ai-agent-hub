export interface LoginRequest {
  tenantSlug: string;
  email: string;
  password: string;
}

export interface RegisterRequest {
  tenantName: string;
  tenantSlug: string;
  email: string;
  password: string;
}

export interface AuthResponse {
  token: string;
  expiresInSeconds: number;
  tenantId: string;
  tenantSlug: string;
  userId: string;
  email: string;
  role: Role;
}

export type Role = 'ADMIN' | 'DEVELOPER' | 'READONLY';
