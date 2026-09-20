/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

/** One key as GET /api/api-keys returns it; never carries the key value. */
export interface ApiKey {
  id: string;
  name: string;
  createdAt: string;
  /** Always null for now - nothing validates keys, so no use is recorded. */
  lastUsedAt: string | null;
  expiresAt: string | null;
  revokedAt: string | null;
  /** Last four characters of the value; null for keys created before it was recorded. */
  keyHint: string | null;
  /** Set when a rotation superseded the key; it keeps working until expiresAt. */
  replacedAt: string | null;
}

/** What POST /api/api-keys answers: the new key plus the only copy of its value. */
export interface CreatedApiKey {
  key: ApiKey;
  value: string;
  /** False when Gravitee kept the key but refused the expiration, so the key never expires. */
  expirationAccepted: boolean;
}

/** Personal API keys of the logged-in user; Gravitee mints them behind the backend. */
@Injectable({
  providedIn: 'root'
})
export class ApiKeyService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/api-keys`;

  list(): Observable<ApiKey[]> {
    return this.http.get<ApiKey[]>(this.baseUrl);
  }

  /**
   * Creates a key; the value in the response is the only copy that will ever exist.
   *
   * @param expiresAt ISO instant the key stops working, null for never
   */
  create(name: string, expiresAt: string | null): Observable<CreatedApiKey> {
    return this.http.post<CreatedApiKey>(this.baseUrl, { name, expiresAt });
  }

  /**
   * Replaces the key with a new one; the value in the response is its only copy. The old key keeps
   * working for Gravitee's two-hour grace period.
   */
  renew(id: string): Observable<CreatedApiKey> {
    return this.http.post<CreatedApiKey>(`${this.baseUrl}/${id}/renew`, {});
  }

  /** Stops the key working immediately; it stays in the list as revoked. */
  revoke(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
