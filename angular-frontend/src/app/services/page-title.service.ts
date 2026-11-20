/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Injectable, inject } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';

const CATALOG_NAME = 'MidPoint Integration Catalog';

/**
 * Gives every screen its own browser tab title (and so a recognizable browser history entry):
 * the route's static `title`, replaced by {@link setPageTitle} on screens whose name is only known after loading.
 */
@Injectable({ providedIn: 'root' })
export class PageTitleService extends TitleStrategy {
  private readonly title = inject(Title);

  override updateTitle(snapshot: RouterStateSnapshot): void {
    this.setPageTitle(this.buildTitle(snapshot));
  }

  /** Sets the tab title to `pageTitle | MidPoint Integration Catalog`, or just the catalog name when empty. */
  setPageTitle(pageTitle: string | null | undefined): void {
    this.title.setTitle(pageTitle ? `${pageTitle} | ${CATALOG_NAME}` : CATALOG_NAME);
  }
}
