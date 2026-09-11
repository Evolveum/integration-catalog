/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../environments/environment';

/** External document links served by GET /api/links (the backend's catalog.links.* properties). */
export interface ExternalLinks {
  termsOfUse: string;
  acceptableUsePolicy: string;
  copyrightGuidelines: string;
  githubTokenGuide: string;
}

/**
 * External document links, loaded once from the backend so they can change without a code change.
 * Until they arrive (or if the request fails) links() is null and the anchors render without a target.
 */
@Injectable({
  providedIn: 'root'
})
export class LinksService {
  private readonly _links = signal<ExternalLinks | null>(null);

  readonly links = this._links.asReadonly();

  constructor(private http: HttpClient) {
    this.http.get<ExternalLinks>(`${environment.apiUrl}/links`).subscribe({
      next: links => this._links.set(links),
      // No guessed fallback URLs - the anchors just stay without a target.
      error: () => {}
    });
  }
}
