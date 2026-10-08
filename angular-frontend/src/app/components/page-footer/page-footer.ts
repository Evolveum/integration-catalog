/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, computed, inject } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import { FooterService, FooterSocial } from '../../services/footer.service';

type LinkTarget =
  | { kind: 'external'; href: string }
  | { kind: 'internal'; path: string }
  | { kind: 'none' };

/** Fixed order of the social icons; only the URLs come from the backend. */
const SOCIAL_NETWORKS: { key: keyof FooterSocial; label: string; icon: string }[] = [
  { key: 'linkedin', label: 'LinkedIn', icon: 'fa-linkedin' },
  { key: 'youtube', label: 'YouTube', icon: 'fa-youtube' },
  { key: 'bluesky', label: 'Bluesky', icon: 'fa-bluesky' },
  { key: 'mastodon', label: 'Mastodon', icon: 'fa-mastodon' },
  { key: 'github', label: 'GitHub', icon: 'fa-github' }
];

@Component({
  selector: 'app-page-footer',
  imports: [NgTemplateOutlet, RouterLink],
  templateUrl: './page-footer.html',
  styleUrls: ['./page-footer.scss']
})
export class PageFooter {
  protected readonly content = inject(FooterService).content;

  protected readonly year = new Date().getFullYear();

  /** An empty URL hides the icon, "#" keeps it as a placeholder without a link. */
  protected readonly socialLinks = computed(() => {
    const social = this.content()?.social;
    return social
      ? SOCIAL_NETWORKS.filter(n => social[n.key]?.trim()).map(n => ({ ...n, target: this.target(social[n.key]) }))
      : [];
  });

  /** Absolute URLs (any scheme) open in a new tab, paths go through the router, "#"/empty is plain text. */
  protected target(link: string | null): LinkTarget {
    const value = link?.trim() ?? '';
    if (!value || value === '#') {
      return { kind: 'none' };
    }
    if (/^[a-z][a-z0-9+.-]*:/i.test(value)) {
      return { kind: 'external', href: value };
    }
    return { kind: 'internal', path: '/' + value.replace(/^\.?\/+/, '') };
  }
}
