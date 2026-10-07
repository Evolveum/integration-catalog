/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../environments/environment';

export interface FooterLink {
  label: string;
  /** Absolute URL, an in-app path such as ./support-tiers, or "#"/empty for a label without a link. */
  link: string | null;
}

export interface FooterColumn {
  label: string;
  items: FooterLink[];
}

export interface FooterSocial {
  linkedin: string | null;
  youtube: string | null;
  bluesky: string | null;
  mastodon: string | null;
  github: string | null;
}

/** Footer content served by GET /api/footer (the backend's catalog.footer.* properties, already limited to 4x4). */
export interface FooterContent {
  columns: FooterColumn[];
  social: FooterSocial;
  bottomLinks: FooterLink[];
}

/**
 * Footer content, loaded once from the backend so links can change without a code change.
 * Until it arrives (or if the request fails) content() is null and the footer shows only the brand.
 */
@Injectable({
  providedIn: 'root'
})
export class FooterService {
  private readonly _content = signal<FooterContent | null>(null);

  readonly content = this._content.asReadonly();

  constructor(private http: HttpClient) {
    this.http.get<FooterContent>(`${environment.apiUrl}/footer`).subscribe({
      next: content => this._content.set(content),
      error: () => {}
    });
  }
}
