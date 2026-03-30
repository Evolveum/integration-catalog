/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Injectable, signal } from '@angular/core';

export enum UserRole {
  Unauthenticated = 'Unauthenticated user',
  ReadOnly = 'Read only',
  IndividualContributor = 'Individual contributor',
  OrganizationContributor = 'Organization contributor',
  Superuser = 'Superuser'
}

const userRoleMap: Record<string, UserRole> = {
  u1: UserRole.Unauthenticated,
  u2: UserRole.ReadOnly,
  u3: UserRole.IndividualContributor,
  u4: UserRole.OrganizationContributor,
  u5: UserRole.Superuser
};

@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private readonly _currentUser = signal<string | null>(null);

  // Expose as a readonly signal property instead of a method
  readonly currentUser = this._currentUser.asReadonly();

  // Simple hardcoded users
  private readonly validUsers = ['u1', 'u2', 'u3', 'u4', 'u5'];

  constructor() {
    // Check if user is already logged in from localStorage
    const storedUser = localStorage.getItem('currentUser');
    if (storedUser && this.validUsers.includes(storedUser)) {
      this._currentUser.set(storedUser);
    }
  }

  login(username: string, password: string): boolean {
    // Simple validation: username must match password and be in valid users
    if (this.validUsers.includes(username) && username === password) {
      this._currentUser.set(username);
      localStorage.setItem('currentUser', username);
      return true;
    }
    return false;
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
