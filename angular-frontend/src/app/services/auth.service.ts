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

interface LoginResponse {
  username: string;
  role: string;
  organizationId: number | null;
  organizationName: string | null;
}

@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private readonly _currentUser = signal<string | null>(null);
  private readonly _currentRole = signal<UserRole | null>(null);

  readonly currentUser = this._currentUser.asReadonly();

  constructor(private http: HttpClient) {
    const storedUser = localStorage.getItem('currentUser');
    const storedRole = localStorage.getItem('currentRole');
    if (storedUser) {
      this._currentUser.set(storedUser);
    }
    if (storedRole) {
      this._currentRole.set(UserRole[storedRole as keyof typeof UserRole] ?? null);
    }
  }

  login(username: string, password: string): Observable<boolean> {
    return this.http.post<LoginResponse>(`${environment.apiUrl}/auth/login`, { username, password }).pipe(
      map(response => {
        const role = UserRole[response.role as keyof typeof UserRole] ?? null;
        this._currentUser.set(response.username);
        this._currentRole.set(role);
        localStorage.setItem('currentUser', response.username);
        if (role) {
          localStorage.setItem('currentRole', response.role);
        }
        return true;
      }),
      catchError(() => of(false))
    );
  }

  logout(): void {
    this._currentUser.set(null);
    this._currentRole.set(null);
    localStorage.removeItem('currentUser');
    localStorage.removeItem('currentRole');
  }

  isLoggedIn(): boolean {
    return this._currentUser() !== null;
  }

  currentRole(): UserRole | null {
    return this._currentRole();
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
