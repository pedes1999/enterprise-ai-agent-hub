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
  /** True until an admin-invited user sets their own password -- see UserService.create() / POST /auth/change-password. */
  mustChangePassword: boolean;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

export type Role = 'ADMIN' | 'DEVELOPER' | 'READONLY';
