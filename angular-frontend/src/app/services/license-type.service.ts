/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Injectable, computed, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../environments/environment';

/** One license served by GET /api/license-types (the backend's LicenseType enum). */
export interface LicenseType {
  name: string;         // enum constant, what the API sends and stores
  displayName: string;  // how it is shown
}

/**
 * The licenses a connector may be published under, loaded once from the backend so every form
 * offers - and every page labels - the same list. Until it arrives a license shows as its constant.
 */
@Injectable({
  providedIn: 'root'
})
export class LicenseTypeService {
  private readonly _options = signal<LicenseType[]>([]);

  readonly options = this._options.asReadonly();

  private readonly labels = computed(() => new Map(this._options().map(o => [o.name, o.displayName])));

  constructor(private http: HttpClient) {
    this.http.get<LicenseType[]>(`${environment.apiUrl}/license-types`).subscribe({
      next: options => this._options.set(options),
      error: () => {}
    });
  }

  /** The display name of a license constant, or '—' when there is none. */
  label(name: string | null | undefined): string {
    if (!name) return '—';
    return this.labels().get(name) ?? name;
  }
}
