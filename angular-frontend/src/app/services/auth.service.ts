/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map, catchError, of } from 'rxjs';
import { environment } from '../../environments/environment';

export enum UserRole {
  Unauthenticated = 'Unauthenticated user',
  ReadOnly = 'Read only',
  IndividualContributor = 'Individual contributor',
  OrganizationContributor = 'Organization contributor',
  Superuser = 'Superuser'
}

const userRoleMap: Record<string, UserRole> = {
  u1: UserRole.OrganizationContributor,
  u2: UserRole.ReadOnly,
  u3: UserRole.IndividualContributor,
  u4: UserRole.IndividualContributor,
  u5: UserRole.Superuser
};

interface LoginResponse {
  username: string;
  organizationId: number | null;
  organizationName: string | null;
}

@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private readonly _currentUser = signal<string | null>(null);

  // Expose as a readonly signal property instead of a method
  readonly currentUser = this._currentUser.asReadonly();

  constructor(private http: HttpClient) {
    // Restore session from localStorage
    const storedUser = localStorage.getItem('currentUser');
    if (storedUser) {
      this._currentUser.set(storedUser);
    }
  }

  login(username: string, password: string): Observable<boolean> {
    return this.http.post<LoginResponse>(`${environment.apiUrl}/auth/login`, { username, password }).pipe(
      map(response => {
        this._currentUser.set(response.username);
        localStorage.setItem('currentUser', response.username);
        return true;
      }),
      catchError(() => of(false))
    );
  }

  logout(): void {
    this._currentUser.set(null);
    localStorage.removeItem('currentUser');
  }

  isLoggedIn(): boolean {
    return this._currentUser() !== null;
  }

  currentRole(): UserRole | null {
    const user = this._currentUser();
    return user ? (userRoleMap[user] ?? null) : null;
  }

  canVote(): boolean {
    const role = this.currentRole();
    return role === UserRole.ReadOnly ||
           role === UserRole.IndividualContributor ||
           role === UserRole.OrganizationContributor ||
           role === UserRole.Superuser;
  }

  canRequest(): boolean {
    return this.canVote();
  }

  canUpload(): boolean {
    const role = this.currentRole();
    return role === UserRole.IndividualContributor ||
           role === UserRole.OrganizationContributor ||
           role === UserRole.Superuser;
  }
}
