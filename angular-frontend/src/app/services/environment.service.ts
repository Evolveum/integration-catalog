/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Injectable, computed, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../environments/environment';

/** Deployment environment served by GET /api/environment. */
interface EnvironmentResponse {
  /** 'inStaging' or 'production' — the backend's catalog.environment property. */
  environment: string;
}

/**
 * Mirrors the backend's deployment environment (GET /api/environment, public).
 * The staging banner is shown only when the backend reports 'inStaging' — if the
 * request fails or the value is anything else, the banner stays hidden.
 */
@Injectable({
  providedIn: 'root'
})
export class EnvironmentService {
  private readonly _environment = signal<string | null>(null);

  readonly isStaging = computed(() => this._environment() === 'inStaging');

  constructor(private http: HttpClient) {
    this.http.get<EnvironmentResponse>(`${environment.apiUrl}/environment`).subscribe({
      next: response => this._environment.set(response.environment),
      // Unknown environment — keep the banner hidden.
      error: () => {}
    });
  }
}
