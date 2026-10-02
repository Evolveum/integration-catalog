/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, computed, effect, inject, signal } from '@angular/core';
import { CommonModule, Location } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../services/auth.service';
import { ApplicationService } from '../../services/application.service';
import { ToastService } from '../../services/toast.service';
import { PageHeader } from '../page-header/page-header';
import { MyConnector, MyConnectorVersion } from '../../models/my-items.model';

/** One connector; `versions` holds only those the filters let through. */
interface ConnectorGroup {
  id: number;
  displayName: string | null;
  isOrganization: boolean;
  latestVersion: string | null;
  versionCount: number;
  usedByCount: number;
  applicationIds: Set<string>;
  versions: MyConnectorVersion[];
}

/**
 * Self-service list of the connectors the user maintains, personally or through their
 * organization, with each version and the integration methods using it.
 */
@Component({
  selector: 'app-my-connectors-page',
  standalone: true,
  imports: [CommonModule, RouterLink, PageHeader],
  templateUrl: './my-connectors-page.html',
  styleUrls: ['./my-connectors-page.scss'],
  host: { '(document:keydown.escape)': 'closeMenu()' }
})
export class MyConnectorsPage {
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly applicationService = inject(ApplicationService);
  private readonly toastService = inject(ToastService);
  protected readonly authService = inject(AuthService);

  protected readonly connectors = signal<MyConnector[] | null>(null);
  protected readonly loading = signal(false);
  protected readonly loadFailed = signal(false);

  // Multi-select like the homepage chips: an empty set lets everything through.
  protected readonly usages = signal<ReadonlySet<string>>(new Set());
  protected readonly applications = signal<ReadonlySet<string>>(new Set());
  protected readonly authors = signal<ReadonlySet<string>>(new Set());
  protected readonly organizationOnly = signal(false);
  protected readonly search = signal('');

  protected readonly usageOptions = [
    { value: 'used', label: 'In use' },
    { value: 'unused', label: 'Not used' }
  ];

  protected readonly collapsedGroups = signal<ReadonlySet<number>>(new Set());
  /** `<connectorId>:<version>` of the version rows whose usage list is open. */
  protected readonly expandedUsages = signal<ReadonlySet<string>>(new Set());
  protected readonly openMenuKey = signal<string | null>(null);

  /** Hidden until it is decided how a new connector version is added; flip to show it again. */
  protected readonly showNewVersion = false;

  private readonly allGroups = computed<ConnectorGroup[]>(() =>
    (this.connectors() ?? []).map(c => {
      const usages = c.versions.flatMap(v => v.usedBy);
      return {
        id: c.id,
        displayName: c.displayName,
        isOrganization: c.maintainer?.category === 'ORG',
        latestVersion: c.versions[0]?.version ?? null,
        versionCount: c.versions.length,
        usedByCount: new Set(usages.map(u => u.integrationMethodId)).size,
        applicationIds: new Set(usages.map(u => u.applicationId)),
        versions: c.versions
      };
    }));

  protected readonly groups = computed<ConnectorGroup[]>(() => {
    const usages = this.usages();
    const organizationOnly = this.organizationOnly();
    const applications = this.applications();
    const authors = this.authors();
    const query = this.search().trim().toLowerCase();
    return this.allGroups()
      .filter(g => !usages.size || usages.has(g.usedByCount > 0 ? 'used' : 'unused'))
      .filter(g => !organizationOnly || g.isOrganization)
      .filter(g => !applications.size || [...applications].some(id => g.applicationIds.has(id)))
      .filter(g => !query || (g.displayName ?? '').toLowerCase().includes(query))
      .map(g => ({ ...g, versions: g.versions.filter(v => !authors.size || authors.has(v.author ?? '')) }))
      .filter(g => g.versions.length > 0);
  });

  protected readonly applicationOptions = computed(() => {
    const seen = new Map<string, string>();
    for (const c of this.connectors() ?? []) {
      for (const u of c.versions.flatMap(v => v.usedBy)) seen.set(u.applicationId, u.applicationDisplayName ?? '');
    }
    return [...seen.entries()]
      .map(([id, name]) => ({ id, name }))
      .sort((a, b) => a.name.localeCompare(b.name));
  });

  protected readonly authorOptions = computed(() =>
    [...new Set((this.connectors() ?? []).flatMap(c => c.versions.map(v => v.author))
      .filter((a): a is string => !!a))]
      .sort((a, b) => a.localeCompare(b)));

  protected readonly totalCount = computed(() => this.allGroups().length);
  protected readonly organizationCount = computed(() => this.allGroups().filter(g => g.isOrganization).length);
  protected readonly inUseCount = computed(() => this.allGroups().filter(g => g.usedByCount > 0).length);
  protected readonly notUsedCount = computed(() => this.allGroups().filter(g => g.usedByCount === 0).length);

  protected readonly hasFilters = computed(() =>
    this.usages().size > 0 || this.organizationOnly() || this.applications().size > 0
    || this.authors().size > 0 || !!this.search().trim());

  constructor() {
    // The profile loads asynchronously at startup, so a page opened directly waits for it.
    effect(() => {
      if (this.authService.profile() && !this.connectors() && !this.loading() && !this.loadFailed()) {
        this.load();
      }
    });
  }

  protected load(): void {
    this.loading.set(true);
    this.loadFailed.set(false);
    this.applicationService.getMyConnectors().subscribe({
      next: connectors => {
        this.connectors.set(connectors);
        this.loading.set(false);
      },
      error: () => {
        this.loadFailed.set(true);
        this.loading.set(false);
      }
    });
  }

  // ==================== Filters ====================

  /** Behind the stat cards' "View items": shows just that slice, every other filter cleared. */
  protected viewItems(usage: string | null, organizationOnly = false): void {
    this.resetFilters();
    if (usage) this.usages.set(new Set([usage]));
    this.organizationOnly.set(organizationOnly);
  }

  /** Whether the usage filter is exactly this one value, as its stat card sets it. */
  protected isOnlyUsage(usage: string): boolean {
    return this.usages().size === 1 && this.usages().has(usage);
  }

  protected resetFilters(): void {
    this.usages.set(new Set());
    this.applications.set(new Set());
    this.authors.set(new Set());
    this.organizationOnly.set(false);
    this.search.set('');
  }

  protected toggleUsageFilter(value: string): void {
    this.usages.update(set => toggled(set, value));
  }

  protected toggleApplication(id: string): void {
    this.applications.update(set => toggled(set, id));
  }

  protected toggleAuthor(author: string): void {
    this.authors.update(set => toggled(set, author));
  }

  protected clearFilter(filter: 'usages' | 'applications' | 'authors'): void {
    this[filter].set(new Set());
  }

  // ==================== Rows ====================

  protected initials(name: string | null): string {
    const parts = (name ?? '').split('@')[0].split(/[\s._-]+/).filter(Boolean);
    if (parts.length === 0) return '?';
    const tail = parts.length > 1 ? parts[parts.length - 1].charAt(0) : '';
    return (parts[0].charAt(0) + tail).toUpperCase();
  }

  protected isCollapsed(id: number): boolean {
    return this.collapsedGroups().has(id);
  }

  protected toggleGroup(id: number): void {
    this.collapsedGroups.update(collapsed => {
      const next = new Set(collapsed);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  }

  protected usageKey(connectorId: number, version: MyConnectorVersion): string {
    return `${connectorId}:${version.version}`;
  }

  protected toggleUsage(key: string): void {
    this.expandedUsages.update(expanded => {
      const next = new Set(expanded);
      if (!next.delete(key)) next.add(key);
      return next;
    });
  }

  protected toggleMenu(key: string): void {
    this.openMenuKey.update(open => open === key ? null : key);
  }

  protected closeMenu(): void {
    this.openMenuKey.set(null);
  }

  /** A new connector version has no flow outside an integration method yet. */
  protected comingSoon(label: string): void {
    this.toastService.show(label, 'This is not available yet. Connectors are added through an integration method.', 'info');
  }

  /** Returns where the user came from, or to the catalog when opened directly. */
  protected goBack(): void {
    if (window.history.length > 1) {
      this.location.back();
    } else {
      this.router.navigate(['/applications']);
    }
  }
}

/** A copy of the set with the value added, or removed when it was there. */
function toggled(set: ReadonlySet<string>, value: string): ReadonlySet<string> {
  const next = new Set(set);
  if (!next.delete(value)) next.add(value);
  return next;
}
