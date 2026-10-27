/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { inject, Injectable } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';

const STORAGE_KEY = 'ic-navigation-history';
const MAX_ENTRIES = 10;

/** One visited in-app address. */
export interface VisitedPage {
  url: string;
  at: string;
}

/**
 * The last few in-app addresses, for the error page's issue report (a page cannot read the
 * browser's own history). Kept in sessionStorage so it survives the full reload of a backend
 * error redirect; without storage it simply starts empty.
 */
@Injectable({
  providedIn: 'root'
})
export class NavigationHistoryService {
  private readonly router = inject(Router);
  private entries: VisitedPage[] = this.restore();

  constructor() {
    this.router.events
      .pipe(filter((event): event is NavigationEnd => event instanceof NavigationEnd))
      .subscribe(event => this.record(event.urlAfterRedirects));
  }

  /** Oldest first. */
  recent(): VisitedPage[] {
    return [...this.entries];
  }

  private record(url: string): void {
    if (this.entries.at(-1)?.url === url) {
      return;
    }
    this.entries = [...this.entries, { url, at: new Date().toISOString() }].slice(-MAX_ENTRIES);
    try {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(this.entries));
    } catch {
      // Storage blocked or full: the in-memory list still works for this page load.
    }
  }

  private restore(): VisitedPage[] {
    try {
      const stored = JSON.parse(sessionStorage.getItem(STORAGE_KEY) ?? '[]');
      return Array.isArray(stored) ? stored.slice(-MAX_ENTRIES) : [];
    } catch {
      return [];
    }
  }
}
