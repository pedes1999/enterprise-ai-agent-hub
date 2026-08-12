import { Role } from './auth.model';

export interface UserSummary {
  id: string;
  email: string;
  name: string;
  role: Role;
  createdAt: string;
}

export interface CreateUserRequest {
  email: string;
  name: string;
  role: Role;
}

export interface UpdateUserRoleRequest {
  role: Role;
}
