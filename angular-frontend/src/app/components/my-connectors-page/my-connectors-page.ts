/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, computed, effect, inject, linkedSignal, signal } from '@angular/core';
import { CommonModule, Location } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService, UserRole } from '../../services/auth.service';
import { ApplicationService } from '../../services/application.service';
import { ToastService } from '../../services/toast.service';
import { PageHeader } from '../page-header/page-header';
import { SUPPORT_TIERS, SupportTier, supportTierOfTag } from '../../core/support-tier';
import { Pager } from '../pager/pager';
import { FilterMenu } from '../filter-menu/filter-menu';
import { ConnectorTagsChange, ConnectorTagsModal } from '../connector-tags-modal/connector-tags-modal';
import { MyConnector, MyConnectorUsage, MyConnectorVersion } from '../../models/my-items.model';
import { ConnectorTag, OBSOLETE_CONNECTOR_TAG, isObsoleteConnector } from '../../models/connector-tag.model';
import {
  MAINTAINER_CATEGORY_LABELS, MaintainerCategory, maintainerFilterOptions, maintainerKey, maintainerLabel
} from '../../models/maintainer.model';

/** One connector; `versions` holds only those the filters let through. */
interface ConnectorGroup {
  id: number;
  displayName: string | null;
  maintainerKey: string;
  maintainerLabel: string;
  maintainerCategory: MaintainerCategory | null;
  tags: ConnectorTag[];
  supportTier: SupportTier | null;
  obsolete: boolean;
  latestVersion: string | null;
  versionCount: number;
  usedByCount: number;
  applicationIds: Set<string>;
  versions: MyConnectorVersion[];
}

/**
 * Self-service list of the connectors the user maintains, personally or through their
 * organization, with each version and the integration methods using it. With the route's
 * `scope: 'all'` it lists every connector in the catalog instead, for a superuser.
 */
@Component({
  selector: 'app-my-connectors-page',
  standalone: true,
  imports: [CommonModule, RouterLink, PageHeader, Pager, ConnectorTagsModal, FilterMenu],
  templateUrl: './my-connectors-page.html',
  styleUrls: ['./my-connectors-page.scss'],
  host: { '(document:keydown.escape)': 'closeMenu(); closeTags()' }
})
export class MyConnectorsPage {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly location = inject(Location);
  private readonly applicationService = inject(ApplicationService);
  private readonly toastService = inject(ToastService);
  protected readonly authService = inject(AuthService);

  /** Every connector in the catalog (superuser) instead of the user's own. */
  protected readonly allConnectors = this.route.snapshot.data['scope'] === 'all';
  protected readonly isSuperuser = computed(() => this.authService.currentRole() === UserRole.Superuser);

  protected readonly connectors = signal<MyConnector[] | null>(null);
  protected readonly loading = signal(false);
  protected readonly loadFailed = signal(false);

  // Multi-select like the homepage chips: an empty set lets everything through.
  protected readonly usages = signal<ReadonlySet<string>>(new Set());
  /** Version states and connector tags to show (all-connectors page only). */
  protected readonly states = signal<ReadonlySet<string>>(new Set());
  protected readonly tags = signal<ReadonlySet<string>>(new Set());
  protected readonly applications = signal<ReadonlySet<string>>(new Set());
  protected readonly authors = signal<ReadonlySet<string>>(new Set());
  /** Keys of the maintainers to show (all-connectors page only). */
  protected readonly maintainers = signal<ReadonlySet<string>>(new Set());
  /** Set by the Organization / Evolveum / Community stat cards. */
  protected readonly maintainerCategory = signal<MaintainerCategory | null>(null);
  protected readonly search = signal('');

  protected readonly stateOptions: { value: string; label: string }[] = [
    { value: 'ACTIVE', label: 'Published' },
    { value: 'IN_REVIEW', label: 'Awaiting approval' },
    { value: 'REVIEWING', label: 'Under review' },
    { value: 'REJECTED', label: 'Rejected' }
  ];

  protected readonly usageOptions = [
    { value: 'used', label: 'In use' },
    { value: 'unused', label: 'Not used' }
  ];

  protected readonly collapsedGroups = signal<ReadonlySet<number>>(new Set());
  /** `<connectorId>:<version>` of the version rows whose usage list is open. */
  protected readonly expandedUsages = signal<ReadonlySet<string>>(new Set());
  protected readonly openMenuKey = signal<string | null>(null);
  /** The connector whose Tags modal is open. */
  protected readonly tagsGroup = signal<ConnectorGroup | null>(null);
  protected readonly tagsSaving = signal(false);
  protected readonly tagsError = signal('');

  /** Hidden until it is decided how a new connector version is added; flip to show it again. */
  protected readonly showNewVersion = false;

  private readonly allGroups = computed<ConnectorGroup[]>(() =>
    (this.connectors() ?? []).map(c => {
      const usages = c.versions.flatMap(v => v.usedBy);
      return {
        id: c.id,
        displayName: c.displayName,
        maintainerKey: maintainerKey(c.maintainer),
        maintainerLabel: maintainerLabel(c.maintainer),
        maintainerCategory: c.maintainer?.category ?? null,
        tags: c.tags ?? [],
        supportTier: (c.tags ?? []).map(t => supportTierOfTag(t.name)).find(t => !!t) ?? null,
        obsolete: isObsoleteConnector(c.tags),
        latestVersion: c.versions[0]?.version ?? null,
        versionCount: c.versions.length,
        usedByCount: new Set(usages.map(u => u.integrationMethodId)).size,
        applicationIds: new Set(usages.map(u => u.applicationId)),
        versions: c.versions.map(v => ({ ...v, usedBy: oneUsagePerMethod(v.usedBy) }))
      };
    }));

  protected readonly groups = computed<ConnectorGroup[]>(() => {
    const usages = this.usages();
    const maintainers = this.maintainers();
    const category = this.maintainerCategory();
    const applications = this.applications();
    const authors = this.authors();
    const states = this.states();
    const tags = this.tags();
    const query = this.search().trim().toLowerCase();
    return this.allGroups()
      .filter(g => !usages.size || usages.has(g.usedByCount > 0 ? 'used' : 'unused'))
      .filter(g => !maintainers.size || maintainers.has(g.maintainerKey))
      .filter(g => !category || g.maintainerCategory === category)
      .filter(g => !applications.size || [...applications].some(id => g.applicationIds.has(id)))
      .filter(g => !tags.size || g.tags.some(tag => tags.has(tag.displayName)))
      .filter(g => !query || (g.displayName ?? '').toLowerCase().includes(query))
      .map(g => ({
        ...g,
        versions: g.versions.filter(v =>
          (!authors.size || authors.has(v.author ?? '')) && (!states.size || states.has(v.lifecycleState ?? '')))
      }))
      .filter(g => g.versions.length > 0);
  });

  protected readonly pageSize = 10;
  /** Back to the first page whenever a filter changes; a reload after an action keeps the page. */
  private readonly currentPage = linkedSignal({
    source: computed(() => [this.usages(), this.states(), this.tags(), this.applications(), this.authors(), this.maintainers(), this.maintainerCategory(), this.search()]),
    computation: () => 0
  });
  protected readonly totalPages = computed(() => Math.ceil(this.groups().length / this.pageSize));
  /** The current page, kept in range when a reload leaves fewer pages. */
  protected readonly page = computed(() => Math.min(this.currentPage(), Math.max(this.totalPages() - 1, 0)));
  protected readonly pagedGroups = computed(() =>
    this.groups().slice(this.page() * this.pageSize, (this.page() + 1) * this.pageSize));

  protected goToPage(page: number): void {
    this.currentPage.set(page);
    window.scrollTo({ top: 0 });
  }

  protected readonly applicationOptions = computed(() => {
    const seen = new Map<string, string>();
    for (const c of this.connectors() ?? []) {
      for (const u of c.versions.flatMap(v => v.usedBy)) seen.set(u.applicationId, u.applicationDisplayName ?? '');
    }
    return [...seen.entries()]
      .map(([value, label]) => ({ value, label }))
      .sort((a, b) => a.label.localeCompare(b.label));
  });

  protected readonly authorOptions = computed(() =>
    [...new Set((this.connectors() ?? []).flatMap(c => c.versions.map(v => v.author))
      .filter((a): a is string => !!a))]
      .sort((a, b) => a.localeCompare(b))
      .map(a => ({ value: a, label: a })));

  protected readonly tagOptions = computed(() =>
    [...new Set((this.connectors() ?? []).flatMap(c => (c.tags ?? []).map(t => t.displayName)))].sort((a, b) => a.localeCompare(b))
      .map(t => ({ value: t, label: t })));

  protected readonly maintainerOptions = computed(() =>
    maintainerFilterOptions((this.connectors() ?? []).map(c => c.maintainer)));

  protected readonly totalCount = computed(() => this.allGroups().length);
  protected readonly organizationCount = computed(() => this.countCategory('ORG'));
  protected readonly evolveumCount = computed(() => this.countCategory('EVOLVEUM'));
  protected readonly communityCount = computed(() => this.countCategory('COMMUNITY'));
  protected readonly inUseCount = computed(() => this.allGroups().filter(g => g.usedByCount > 0).length);
  protected readonly notUsedCount = computed(() => this.allGroups().filter(g => g.usedByCount === 0).length);

  protected readonly hasFilters = computed(() =>
    this.usages().size > 0 || this.states().size > 0 || this.tags().size > 0
    || !!this.maintainerCategory() || this.maintainers().size > 0 || this.applications().size > 0
    || this.authors().size > 0 || !!this.search().trim());

  constructor() {
    // The profile loads asynchronously at startup, so a page opened directly waits for it.
    effect(() => {
      if (this.authService.profile() && (!this.allConnectors || this.isSuperuser())
          && !this.connectors() && !this.loading() && !this.loadFailed()) {
        this.load();
      }
    });
  }

  protected load(): void {
    this.loading.set(true);
    this.loadFailed.set(false);
    const connectors$ = this.allConnectors
        ? this.applicationService.getAllConnectors()
        : this.applicationService.getMyConnectors();
    connectors$.subscribe({
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

  private countCategory(category: MaintainerCategory): number {
    return this.allGroups().filter(g => g.maintainerCategory === category).length;
  }

  protected categoryLabel(category: MaintainerCategory): string {
    return MAINTAINER_CATEGORY_LABELS[category];
  }

  // ==================== Filters ====================

  /** Behind the stat cards' "View items": shows just that slice, every other filter cleared. */
  protected viewItems(usage: string | null, category: MaintainerCategory | null = null): void {
    this.resetFilters();
    if (usage) this.usages.set(new Set([usage]));
    // Where the Maintainer dropdown exists the card selects its maintainers there; the own-items
    // page has no such dropdown, so the Organization card keeps the category chip.
    if (category && this.allConnectors) this.maintainers.set(this.categoryMaintainerKeys(category));
    else this.maintainerCategory.set(category);
  }

  /** Keys of the maintainers of this category among the loaded items. */
  private categoryMaintainerKeys(category: MaintainerCategory): Set<string> {
    return new Set(this.allGroups().filter(g => g.maintainerCategory === category).map(g => g.maintainerKey));
  }

  /** Whether the maintainer filter is exactly this category's maintainers, as its stat card sets it. */
  protected isOnlyCategory(category: MaintainerCategory): boolean {
    const keys = this.categoryMaintainerKeys(category);
    return keys.size > 0 && this.maintainers().size === keys.size && [...keys].every(key => this.maintainers().has(key));
  }

  /** Whether the usage filter is exactly this one value, as its stat card sets it. */
  protected isOnlyUsage(usage: string): boolean {
    return this.usages().size === 1 && this.usages().has(usage);
  }

  protected resetFilters(): void {
    this.usages.set(new Set());
    this.states.set(new Set());
    this.tags.set(new Set());
    this.applications.set(new Set());
    this.authors.set(new Set());
    this.maintainers.set(new Set());
    this.maintainerCategory.set(null);
    this.search.set('');
  }

  protected usageLabel(value: string): string {
    return this.usageOptions.find(o => o.value === value)?.label ?? value;
  }

  protected toggleApplication(id: string): void {
    this.applications.update(set => toggled(set, id));
  }

  protected toggleAuthor(author: string): void {
    this.authors.update(set => toggled(set, author));
  }

  protected toggleState(value: string): void {
    this.states.update(set => toggled(set, value));
  }

  protected toggleTag(tag: string): void {
    this.tags.update(set => toggled(set, tag));
  }

  protected toggleMaintainer(key: string): void {
    this.maintainers.update(set => toggled(set, key));
  }

  protected clearFilter(filter: 'usages' | 'states' | 'tags' | 'applications' | 'authors' | 'maintainers'): void {
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

  protected openTags(group: ConnectorGroup): void {
    this.closeMenu();
    this.tagsError.set('');
    this.tagsGroup.set(group);
  }

  protected closeTags(): void {
    if (!this.tagsSaving()) this.tagsGroup.set(null);
  }

  /** Colors a tier tag like the tier badges; '' for an ordinary tag. */
  protected tierClass(tag: ConnectorTag): string {
    const tier = supportTierOfTag(tag.name);
    return tier ? 'tier-' + tier.toLowerCase() : '';
  }

  /** The modal's Save: swaps the connector's tier and obsolete tags, here as on the server. */
  protected saveTags(change: ConnectorTagsChange): void {
    const group = this.tagsGroup();
    if (!group) return;
    this.tagsSaving.set(true);
    this.tagsError.set('');
    this.applicationService.setConnectorTags(group.id, change.tier, change.obsolete).subscribe({
      next: () => {
        this.connectors.update(connectors => (connectors ?? []).map(c =>
          c.id === group.id ? { ...c, tags: withManagedTags(c.tags ?? [], change) } : c));
        this.tagsSaving.set(false);
        this.tagsGroup.set(null);
      },
      error: () => {
        this.tagsSaving.set(false);
        this.tagsError.set('Saving the tags failed. Please try again.');
      }
    });
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

/** The tags with the tier and obsolete tags set as in {@code change}; sorted as the server sends them. */
function withManagedTags(tags: ConnectorTag[], change: ConnectorTagsChange): ConnectorTag[] {
  const others = tags.filter(t => !supportTierOfTag(t.name) && t.name !== OBSOLETE_CONNECTOR_TAG);
  const tierTag = SUPPORT_TIERS.filter(t => t.value === change.tier).map(t => ({ name: t.tagName, displayName: t.label }));
  const obsoleteTag = change.obsolete ? [{ name: OBSOLETE_CONNECTOR_TAG, displayName: 'Obsolete' }] : [];
  return [...others, ...tierTag, ...obsoleteTag]
    .sort((a, b) => a.displayName.localeCompare(b.displayName, undefined, { sensitivity: 'base' }));
}

/** A copy of the set with the value added, or removed when it was there. */
function toggled(set: ReadonlySet<string>, value: string): ReadonlySet<string> {
  const next = new Set(set);
  if (!next.delete(value)) next.add(value);
  return next;
}

/**
 * A method counts once however many of its revisions use the version, as on the connector's card;
 * its newest revision stands for it.
 */
function oneUsagePerMethod(usages: MyConnectorUsage[]): MyConnectorUsage[] {
  const newest = new Map<string, MyConnectorUsage>();
  for (const u of usages) {
    const kept = newest.get(u.integrationMethodId);
    if (!kept || u.revision.localeCompare(kept.revision, undefined, { numeric: true }) > 0) {
      newest.set(u.integrationMethodId, u);
    }
  }
  return [...newest.values()];
}
