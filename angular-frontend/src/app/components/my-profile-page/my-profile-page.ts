/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, computed, effect, inject, signal } from '@angular/core';
import { CommonModule, Location } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { AuthService, UserRole } from '../../services/auth.service';
import { ApplicationService } from '../../services/application.service';
import { PageHeader } from '../page-header/page-header';
import { MyConnector, MyIntegrationMethod, MyItems } from '../../models/my-items.model';

/** All revisions of one integration method, as the list shows them under a single heading. */
interface MethodGroup {
  id: string;
  applicationId: string;
  applicationDisplayName: string | null;
  applicationHasLogo: boolean;
  displayName: string | null;
  revisions: MyIntegrationMethod[];
  publishedCount: number;
  awaitingCount: number;
  reviewingCount: number;
  rejectedCount: number;
}

/**
 * Self-service overview, reached from the account menu: the integration methods and connectors
 * the user maintains, personally or through their organization, with their review state.
 */
@Component({
  selector: 'app-my-profile-page',
  standalone: true,
  imports: [CommonModule, RouterLink, PageHeader],
  templateUrl: './my-profile-page.html',
  styleUrls: ['./my-profile-page.scss'],
  host: { '(document:keydown.escape)': 'closeMenu()' }
})
export class MyProfilePage {
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly applicationService = inject(ApplicationService);
  protected readonly authService = inject(AuthService);

  protected readonly profile = this.authService.profile;
  protected readonly initials = this.authService.initials;

  protected readonly items = signal<MyItems | null>(null);
  protected readonly loading = signal(false);
  protected readonly loadFailed = signal(false);

  protected readonly methodsOpen = signal(true);
  protected readonly connectorsOpen = signal(true);
  protected readonly expandedConnectors = signal<ReadonlySet<number>>(new Set());
  /** Key of the row whose action menu is open: `m:<id>:<revision>`. */
  protected readonly openMenuKey = signal<string | null>(null);

  protected readonly displayName = computed(() =>
    this.profile()?.fullName?.trim() || this.profile()?.username || '');

  protected readonly organization = computed(() =>
    this.profile()?.organizationDisplayName || this.profile()?.organizationName || null);

  /** Revisions arrive newest first per method, so the first one names the group. */
  protected readonly methodGroups = computed<MethodGroup[]>(() => {
    const groups = new Map<string, MethodGroup>();
    for (const revision of this.items()?.integrationMethods ?? []) {
      let group = groups.get(revision.id);
      if (!group) {
        group = {
          id: revision.id,
          applicationId: revision.applicationId,
          applicationDisplayName: revision.applicationDisplayName,
          applicationHasLogo: revision.applicationHasLogo,
          displayName: revision.displayName,
          revisions: [],
          publishedCount: 0,
          awaitingCount: 0,
          reviewingCount: 0,
          rejectedCount: 0
        };
        groups.set(revision.id, group);
      }
      group.revisions.push(revision);
      switch (revision.lifecycleState) {
        case 'ACTIVE': group.publishedCount++; break;
        case 'IN_REVIEW': group.awaitingCount++; break;
        case 'REVIEWING': group.reviewingCount++; break;
        case 'REJECTED': group.rejectedCount++; break;
      }
    }
    return [...groups.values()];
  });

  protected readonly connectors = computed<MyConnector[]>(() => this.items()?.connectors ?? []);

  protected readonly awaitingCount = computed(() =>
    this.methodGroups().reduce((sum, g) => sum + g.awaitingCount, 0));

  protected readonly reviewingCount = computed(() =>
    this.methodGroups().reduce((sum, g) => sum + g.reviewingCount, 0));

  constructor() {
    // The profile loads asynchronously at startup, so a page opened directly waits for it.
    effect(() => {
      if (this.profile() && !this.items() && !this.loading() && !this.loadFailed()) {
        this.load();
      }
    });
  }

  protected load(): void {
    this.loading.set(true);
    this.loadFailed.set(false);
    this.applicationService.getMyItems().subscribe({
      next: items => {
        this.items.set(items);
        this.loading.set(false);
      },
      error: () => {
        this.loadFailed.set(true);
        this.loading.set(false);
      }
    });
  }

  protected logoUrl(applicationId: string): string {
    return this.applicationService.getLogoUrl(applicationId);
  }

  /** The review lock: once a superuser starts reviewing, only a superuser may edit the revision. */
  protected canEdit(revision: MyIntegrationMethod): boolean {
    return revision.lifecycleState !== 'REVIEWING' || this.authService.currentRole() === UserRole.Superuser;
  }

  protected toggleConnector(id: number): void {
    this.expandedConnectors.update(expanded => {
      const next = new Set(expanded);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  }

  protected toggleMenu(key: string): void {
    this.openMenuKey.update(open => open === key ? null : key);
  }

  protected closeMenu(): void {
    this.openMenuKey.set(null);
  }

  protected openDetails(applicationId: string, methodId: string, revision: string): void {
    this.closeMenu();
    this.router.navigate(['/applications', applicationId, 'integration-method', methodId, revision, 'details']);
  }

  protected openEdit(revision: MyIntegrationMethod): void {
    this.closeMenu();
    this.router.navigate(['/applications', revision.applicationId, 'integration-method', revision.id, revision.revision, 'edit']);
  }

  /** Starts the publish flow, which itself asks for the application. */
  protected newIntegrationMethod(): void {
    this.router.navigate(['/approve']);
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
