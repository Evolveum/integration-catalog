/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { computed, inject, Injectable, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpStatusCode } from '@angular/common/http';
import { catchError, map, Observable, of } from 'rxjs';
import { environment } from '../../environments/environment';

export enum UserRole {
  ReadOnly = 'Read only',
  IndividualContributor = 'Individual contributor',
  OrganizationContributor = 'Organization contributor',
  Superuser = 'Superuser'
}

/** Profile served by GET /api/auth/me; a field the provider does not emit is null. */
export interface CurrentUserResponse {
  username: string;
  fullName: string | null;
  firstName: string | null;
  lastName: string | null;
  email: string | null;
  phoneNumber: string | null;
  locale: string | null;
  zoneInfo: string | null;
  role: string;
  /** Organization identifier — stable across organization renames. */
  organizationId: string | null;
  organizationName: string | null;
  /** Where the profile is edited; null when none is configured. */
  iamProfileUrl: string | null;
}

/**
 * Session state for the OIDC login. The backend session is the single source of truth: this only
 * mirrors what GET /api/auth/me reports, and keeps nothing in localStorage.
 */
@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private readonly http = inject(HttpClient);

  private readonly _currentUser = signal<string | null>(null);
  private readonly _currentRole = signal<UserRole | null>(null);
  private readonly _currentOrganizationName = signal<string | null>(null);
  private readonly _currentOrganizationId = signal<string | null>(null);
  private readonly _profile = signal<CurrentUserResponse | null>(null);

  readonly currentUser = this._currentUser.asReadonly();

  /** The whole profile, for pages that show more than the name and role. */
  readonly profile = this._profile.asReadonly();

  /** Letters for the avatar circles: first and last name, else the full name, else the username. */
  readonly initials = computed(() => {
    const profile = this._profile();
    const first = profile?.firstName?.trim();
    const last = profile?.lastName?.trim();
    if (first || last) {
      return ((first?.charAt(0) ?? '') + (last?.charAt(0) ?? '')).toUpperCase();
    }
    // No given/family name in the token: split whatever name there is, e.g. "john.doe" -> "JD".
    const parts = (profile?.fullName?.trim() || profile?.username || '')
      .split('@')[0].split(/[\s._-]+/).filter(Boolean);
    if (parts.length === 0) return '?';
    const tail = parts.length > 1 ? parts[parts.length - 1].charAt(0) : '';
    return (parts[0].charAt(0) + tail).toUpperCase();
  });

  /**
   * Loads the profile of the current backend session. Completes rather than fails on every
   * outcome, so a provider problem cannot keep the application from starting.
   */
  loadCurrentUser(): Observable<void> {
    return this.http.get<CurrentUserResponse>(`${environment.apiUrl}/auth/me`).pipe(
      map(user => this.applyCurrentUser(user)),
      catchError((error: HttpErrorResponse) => {
        this.applyCurrentUser(null);
        if (error.status !== HttpStatusCode.Unauthorized) {
          console.error('Could not load the current user profile; continuing as anonymous.', error);
        }
        return of(undefined);
      })
    );
  }

  /** Mirrors a loaded profile — or, for null, the anonymous state — into the session signals. */
  private applyCurrentUser(user: CurrentUserResponse | null): void {
    this._profile.set(user);
    this._currentUser.set(user?.username ?? null);
    this._currentRole.set(user ? (UserRole[user.role as keyof typeof UserRole] ?? null) : null);
    this._currentOrganizationName.set(user?.organizationName ?? null);
    this._currentOrganizationId.set(user?.organizationId ?? null);
  }

  /**
   * Whether the token names an organization the catalog has no row for, in which case the user
   * publishes as themselves until an administrator seeds it. The header banner says so.
   */
  organizationIsUnregistered(): boolean {
    return this._currentRole() === UserRole.OrganizationContributor
      && !!this._currentOrganizationId()
      && !this._currentOrganizationName();
  }

  currentOrganizationId(): string | null {
    return this._currentOrganizationId();
  }

  /** Starts the OIDC login flow: full-page redirect to the provider via the backend. */
  login(): void {
    window.location.href = `${this.backendBaseUrl()}/oauth2/authorization/oidc`;
  }

  /** Ends both the application session and the provider's SSO session, then returns to the app. */
  logout(): void {
    window.location.href = `${this.backendBaseUrl()}/logout`;
  }

  /** The backend origin the OAuth endpoints live on (apiUrl minus the /api suffix). */
  private backendBaseUrl(): string {
    return environment.apiUrl.replace(/\/api\/?$/, '');
  }

  currentOrganizationName(): string | null {
    return this._currentOrganizationName();
  }

  getAllMaintainers(): Observable<string[]> {
    return this.http.get<string[]>(`${environment.apiUrl}/auth/all-maintainers`);
  }

  /**
   * Default value for the maintainer combobox: an organization contributor maintains on
   * behalf of their organization; everyone else (including superusers) as themselves.
   */
  defaultMaintainer(): string {
    if (this._currentRole() === UserRole.OrganizationContributor) {
      const orgName = this._currentOrganizationName();
      if (orgName) return orgName;
    }
    return this._currentUser() ?? '';
  }

  /**
   * Options for the maintainer combobox of a non-superuser: the organization plus the user
   * themselves for an organization contributor, otherwise just the user.
   */
  maintainerOptions(): string[] {
    const user = this._currentUser();
    if (this._currentRole() === UserRole.OrganizationContributor) {
      const orgName = this._currentOrganizationName();
      if (orgName) return user ? [orgName, user] : [orgName];
    }
    return user ? [user] : [];
  }

  /**
   * Organization shown next to the current user when they are a maintainer. An individual
   * contributor publishes as themselves, so none is shown for them.
   */
  displayedOrganization(): string | null {
    return this._currentRole() === UserRole.OrganizationContributor
      ? this._currentOrganizationName()
      : null;
  }

  /** Display label for a maintainer dropdown option: the logged-in user is marked "(me)". */
  maintainerOptionLabel(option: string): string {
    const user = this._currentUser();
    return user && option.trim().toLowerCase() === user.trim().toLowerCase()
      ? `${option} (me)`
      : option;
  }

  isLoggedIn(): boolean {
    return this._currentUser() !== null;
  }

  currentRole(): UserRole | null {
    return this._currentRole();
  }

  /** Any logged-in user may vote, including ReadOnly; anonymous visitors may not. */
  canVote(): boolean {
    return this.isLoggedIn();
  }

  /** Creating requests requires a contributor role — ReadOnly only browses and votes. */
  canRequest(): boolean {
    const role = this.currentRole();
    return role === UserRole.IndividualContributor ||
           role === UserRole.OrganizationContributor ||
           role === UserRole.Superuser;
  }

  canUpload(): boolean {
    const role = this.currentRole();
    return role === UserRole.IndividualContributor ||
           role === UserRole.OrganizationContributor ||
           role === UserRole.Superuser;
  }

  /**
   * Whether the current user may see/edit an item with the given owners, mirroring the server-side
   * `AuthService.canEdit`. Decides which controls are shown only; the backend re-enforces the rule.
   */
  canEdit(
    author: string | null | undefined,
    authorOrganization: string | null | undefined,
    maintainer?: string | null,
    maintainerOrganization?: string | null,
  ): boolean {
    const user = this._currentUser();
    if (!user) return false;
    const role = this._currentRole();
    if (role === UserRole.Superuser) return true;
    const orgName = this.displayedOrganization();
    if (maintainer) {
      const m = maintainer.trim().toLowerCase();
      if (m === user.trim().toLowerCase()) return true;
      if (orgName && m === orgName.trim().toLowerCase()) return true;
    }
    // An organization acts as a team: a contributor maintainer grants access to all org-mates.
    if (orgName && maintainerOrganization
        && orgName.trim().toLowerCase() === maintainerOrganization.trim().toLowerCase()) {
      return true;
    }
    if (author && author.trim().toLowerCase() === user.trim().toLowerCase()) return true;
    if (orgName && authorOrganization
        && orgName.trim().toLowerCase() === authorOrganization.trim().toLowerCase()) {
      return true;
    }
    return false;
  }
}
