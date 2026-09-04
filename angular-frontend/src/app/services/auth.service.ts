/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { inject, Injectable, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpStatusCode } from '@angular/common/http';
import { catchError, map, Observable, of } from 'rxjs';
import { environment } from '../../environments/environment';

export enum UserRole {
  ReadOnly = 'Read only',
  IndividualContributor = 'Individual contributor',
  OrganizationContributor = 'Organization contributor',
  Superuser = 'Superuser'
}

/** Profile served by GET /api/auth/me for the authenticated session. */
interface CurrentUserResponse {
  username: string;
  fullName: string | null;
  email: string | null;
  role: string;
  /** Organization identifier — stable across organization renames. */
  organizationId: string | null;
  organizationName: string | null;
}

/**
 * Session state for the OIDC login. Authentication is done by the identity provider
 * through the backend (Spring Security OIDC client): login/logout are full-page redirects, the
 * browser carries a session cookie, and this service only mirrors the profile that
 * GET /api/auth/me reports for that session. Nothing identity-related is kept in
 * localStorage anymore — the backend session is the single source of truth.
 */
@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private readonly http = inject(HttpClient);

  private readonly _currentUser = signal<string | null>(null);
  private readonly _currentRole = signal<UserRole | null>(null);
  private readonly _currentOrganizationName = signal<string | null>(null);

  readonly currentUser = this._currentUser.asReadonly();

  /**
   * Loads the profile of the current backend session into the signals above. It completes
   * rather than fails on every outcome, so a provider or backend problem cannot keep the
   * application from starting: a 401 is the normal "no session" answer and leaves the
   * visitor anonymous, any other failure is logged and treated the same way.
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
    this._currentUser.set(user?.username ?? null);
    this._currentRole.set(user ? (UserRole[user.role as keyof typeof UserRole] ?? null) : null);
    this._currentOrganizationName.set(user?.organizationName ?? null);
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
   * Options for the maintainer combobox of a non-superuser: the organization plus the
   * user themselves for an organization contributor, otherwise just the user. Superusers
   * load the full list asynchronously via getAllMaintainers() instead.
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
   * Organization shown next to the current user when they are a maintainer: only an
   * organization contributor is displayed under their org. An individual contributor who
   * belongs to an organization publishes as themselves, so no organization is shown
   * (mirrors the server-side maintainerOrganization resolution in ApplicationMapper).
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
   * Whether the current user may see/edit an item designated by the given maintainer and
   * uploaded by the given author/organization. Mirrors the server-side `AuthService.canEdit`:
   * a Superuser may access anything; the designated maintainer may access it (matched by
   * username, or by the user's organization name when the item is maintained by their org);
   * an item maintained by a member of the user's organization is accessible to the whole
   * organization (pass the maintainer's org as `maintainerOrganization`; a maintainer
   * without an organization stays personal); the uploader may access items they authored;
   * and an Organization contributor may access any item authored by a member of their own
   * organization (same organization name). The server only exposes `maintainerOrganization`\
   * `authorOrganization` when that maintainer\author is an Organization contributor, so items
   * of an Individual contributor who belongs to an org stay personal on both sides.
   *
   * Every organization branch reads displayedOrganization(), not the raw membership: an
   * Individual contributor who happens to belong to an organization gains nothing from it,
   * so their org-mates' drafts stay invisible to them.
   *
   * The maintainer is the primary ownership signal — it is explicitly set at publish time,
   * so e.g. a superuser can attribute an item to another user, who then gains access to it.
   *
   * This only decides which controls/rows are shown — the backend re-enforces the same rule.
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
