/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { PageHeader } from '../page-header/page-header';
import { Pager } from '../pager/pager';
import { versionBadge } from '../../core/version-badge';
import { ApplicationService } from '../../services/application.service';
import { IntegrationMethodTier } from '../../models/application-detail.model';
import { SUPPORT_TIERS, SupportTier, isCoveredBy, supportTierLabel } from '../../core/support-tier';

interface ApplicationGroup {
  applicationId: string;
  applicationName: string;
  methods: IntegrationMethodTier[];
}

/**
 * Every published integration method with a support tier, grouped by application. A tier filter
 * shows what a subscription to that tier covers, i.e. that tier and the ones below it.
 */
@Component({
  selector: 'app-support-tiers-page',
  standalone: true,
  imports: [CommonModule, RouterLink, PageHeader, Pager],
  templateUrl: './support-tiers-page.html',
  styleUrls: ['./support-tiers-page.scss']
})
export class SupportTiersPage implements OnInit {
  private readonly applicationService = inject(ApplicationService);

  protected readonly supportTiers = SUPPORT_TIERS;
  protected readonly supportTierLabel = supportTierLabel;
  protected readonly versionBadge = versionBadge;
  protected readonly loading = signal<boolean>(true);
  protected readonly error = signal<boolean>(false);
  protected readonly methods = signal<IntegrationMethodTier[]>([]);
  /** null = every tiered method. */
  protected readonly subscription = signal<SupportTier | null>(null);

  /** Applications per page. */
  protected readonly pageSize = 10;
  protected readonly currentPage = signal<number>(0);

  /** The methods the subscription covers, in display order: by application, then by method. */
  private readonly filteredMethods = computed<IntegrationMethodTier[]>(() => {
    const subscription = this.subscription();
    return this.methods()
      .filter(m => !!m.supportTier && (!subscription || isCoveredBy(m.supportTier, subscription)))
      .sort((a, b) => (a.applicationName ?? '').localeCompare(b.applicationName ?? '')
        // Keeps two same-named applications from interleaving.
        || a.applicationId.localeCompare(b.applicationId)
        || (a.methodName ?? '').localeCompare(b.methodName ?? ''));
  });

  /** The methods grouped by application, in display order. */
  private readonly allGroups = computed<ApplicationGroup[]>(() => {
    const groups: ApplicationGroup[] = [];
    for (const m of this.filteredMethods()) {
      let group = groups[groups.length - 1];
      if (group?.applicationId !== m.applicationId) {
        group = { applicationId: m.applicationId, applicationName: m.applicationName, methods: [] };
        groups.push(group);
      }
      group.methods.push(m);
    }
    return groups;
  });

  protected readonly totalPages = computed(() => Math.ceil(this.allGroups().length / this.pageSize));

  /** The current page's applications, each with all its methods. */
  protected readonly groups = computed<ApplicationGroup[]>(() =>
    this.allGroups().slice(this.currentPage() * this.pageSize, (this.currentPage() + 1) * this.pageSize));

  protected selectSubscription(tier: SupportTier | null): void {
    this.subscription.set(tier);
    this.currentPage.set(0);
  }

  protected goToPage(page: number): void {
    if (page < 0 || page >= this.totalPages()) return;
    this.currentPage.set(page);
    window.scrollTo({ top: 0 });
  }

  ngOnInit(): void {
    this.applicationService.getSupportTiers().subscribe({
      next: (methods) => {
        this.methods.set(methods);
        this.loading.set(false);
      },
      error: (err) => {
        console.error('Loading support tiers failed', err);
        this.error.set(true);
        this.loading.set(false);
      }
    });
  }
}
