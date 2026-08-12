import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { CreateUserRequest, UpdateUserRoleRequest, UserSummary } from '../models/user.model';

@Injectable({ providedIn: 'root' })
export class UserService {
  private readonly http = inject(HttpClient);

  list(): Observable<UserSummary[]> {
    return this.http.get<UserSummary[]>(`${environment.apiBaseUrl}/users`);
  }

  create(request: CreateUserRequest): Observable<UserSummary> {
    return this.http.post<UserSummary>(`${environment.apiBaseUrl}/users`, request);
  }

  updateRole(userId: string, request: UpdateUserRoleRequest): Observable<UserSummary> {
    return this.http.patch<UserSummary>(`${environment.apiBaseUrl}/users/${userId}/role`, request);
  }

  delete(userId: string): Observable<void> {
    return this.http.delete<void>(`${environment.apiBaseUrl}/users/${userId}`);
  }
}
