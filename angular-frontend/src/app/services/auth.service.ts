/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { computed, inject, Injectable, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpStatusCode } from '@angular/common/http';
import { catchError, map, Observable, of } from 'rxjs';
import { environment } from '../../environments/environment';
import {
  Maintainer,
  maintainerLabel,
  organizationMaintainer,
  sameMaintainer,
  userMaintainer
} from '../models/maintainer.model';

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
  /** The organization's alias — stable across renames, and what a maintainer row points at. */
  organizationName: string | null;
  /** Where the profile is edited; null when none is configured. */
  iamProfileUrl: string | null;
  /** What that organization is called in the catalog; null when it has no row there. */
  organizationDisplayName: string | null;
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
  private readonly _currentOrganizationDisplayName = signal<string | null>(null);
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
    this._currentOrganizationDisplayName.set(user?.organizationDisplayName ?? null);
  }

  /**
   * Whether the token names an organization the catalog has no row for, in which case the user
   * publishes as themselves until an administrator seeds it. The publish form's banner says so.
   */
  organizationIsUnregistered(): boolean {
    return this._currentRole() === UserRole.OrganizationContributor
      && !!this._currentOrganizationName()
      && !this._currentOrganizationDisplayName();
  }

  /** The alias, which is all there is to show when the organization has no row in the catalog. */
  currentOrganizationId(): string | null {
    return this._currentOrganizationName();
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

  /** What the caller's organization is called in the catalog; null when it has no row there. */
  currentOrganizationName(): string | null {
    return this._currentOrganizationDisplayName();
  }

  /** Every maintainer a superuser may choose from, the server deciding what each one is. */
  getAllMaintainers(): Observable<Maintainer[]> {
    return this.http.get<Maintainer[]>(`${environment.apiUrl}/auth/all-maintainers`);
  }

  /**
   * Default value for the maintainer combobox: an organization contributor maintains on
   * behalf of their organization; everyone else (including superusers) as themselves.
   */
  defaultMaintainer(): Maintainer | null {
    if (this._currentRole() === UserRole.OrganizationContributor) {
      const alias = this._currentOrganizationName();
      if (alias) return organizationMaintainer(alias, this._currentOrganizationDisplayName());
    }
    const user = this._currentUser();
    return user ? userMaintainer(user) : null;
  }

  /**
   * Options for the maintainer combobox of a non-superuser: the organization plus the user
   * themselves for an organization contributor, otherwise just the user. A superuser picks from
   * {@link getAllMaintainers} instead.
   */
  maintainerOptions(): Maintainer[] {
    const user = this._currentUser();
    const self = user ? [userMaintainer(user)] : [];
    if (this._currentRole() === UserRole.OrganizationContributor) {
      const alias = this._currentOrganizationName();
      if (alias) {
        return [organizationMaintainer(alias, this._currentOrganizationDisplayName()), ...self];
      }
    }
    return self;
  }

  /**
   * Organization shown next to the current user when they are a maintainer. An individual
   * contributor publishes as themselves, so none is shown for them.
   */
  displayedOrganization(): string | null {
    return this._currentRole() === UserRole.OrganizationContributor
      ? this._currentOrganizationDisplayName()
      : null;
  }

  /** Display label for a maintainer dropdown option: the logged-in user is marked "(me)". */
  maintainerOptionLabel(option: Maintainer): string {
    const label = maintainerLabel(option);
    const user = this._currentUser();
    return user && option.category === 'USER' && sameIgnoringCase(option.username, user)
      ? `${label} (me)`
      : label;
  }

  /** Whether an option is the one currently chosen, so the dropdown can mark it. */
  isSameMaintainer(a: Maintainer | null, b: Maintainer | null): boolean {
    return sameMaintainer(a, b);
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
   * Whether the current user may see/edit an item with this maintainer. A copy of the server-side
   * `AuthService.canEdit`, which is authoritative: change that first, then mirror it here.
   */
  canEdit(maintainer: Maintainer | null | undefined): boolean {
    const user = this._currentUser();
    const role = this._currentRole();
    if (!user || !role || role === UserRole.ReadOnly) return false;
    if (role === UserRole.Superuser) return true;
    if (!maintainer) return false;
    if (maintainer.category === 'COMMUNITY') return true;
    if (sameIgnoringCase(maintainer.username, user)) return true;
    // An organization acts as a team: whatever it maintains, all of its contributors may edit.
    return role === UserRole.OrganizationContributor
      && sameIgnoringCase(maintainer.organizationName, this._currentOrganizationName());
  }
}

function sameIgnoringCase(a: string | null | undefined, b: string | null | undefined): boolean {
  return !!a && !!b && a.toLowerCase() === b.toLowerCase();
}
