/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { PageHeader } from '../page-header/page-header';
import { ApplicationService } from '../../services/application.service';
import { IntegrationMethodTier } from '../../models/application-detail.model';
import { SUPPORT_TIERS, SupportTier, isCoveredBy, supportTierLabel } from '../../core/support-tier';

interface ApplicationGroup {
  applicationId: string;
  applicationName: string;
  methods: IntegrationMethodTier[];
}

/**
 * Every published integration method grouped by application, filtered by support tier. A tier
 * filter shows what a subscription to that tier covers, i.e. that tier and the ones below it.
 */
@Component({
  selector: 'app-support-tiers-page',
  standalone: true,
  imports: [CommonModule, RouterLink, PageHeader],
  templateUrl: './support-tiers-page.html',
  styleUrls: ['./support-tiers-page.scss']
})
export class SupportTiersPage implements OnInit {
  private readonly applicationService = inject(ApplicationService);

  protected readonly supportTiers = SUPPORT_TIERS;
  protected readonly supportTierLabel = supportTierLabel;
  protected readonly loading = signal<boolean>(true);
  protected readonly error = signal<boolean>(false);
  protected readonly methods = signal<IntegrationMethodTier[]>([]);
  /** null = all methods, tiered or not. */
  protected readonly subscription = signal<SupportTier | null>(null);

  protected readonly groups = computed<ApplicationGroup[]>(() => {
    const subscription = this.subscription();
    const byApp = new Map<string, ApplicationGroup>();
    for (const m of this.methods()) {
      if (subscription && !isCoveredBy(m.supportTier, subscription)) continue;
      let group = byApp.get(m.applicationId);
      if (!group) {
        group = { applicationId: m.applicationId, applicationName: m.applicationName, methods: [] };
        byApp.set(m.applicationId, group);
      }
      group.methods.push(m);
    }
    const groups = Array.from(byApp.values());
    groups.sort((a, b) => (a.applicationName ?? '').localeCompare(b.applicationName ?? ''));
    groups.forEach(g => g.methods.sort((a, b) => (a.methodName ?? '').localeCompare(b.methodName ?? '')));
    return groups;
  });

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
