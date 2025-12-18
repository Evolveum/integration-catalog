/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { ApplicationService } from '../../services/application.service';
import { ApplicationDetail as ApplicationDetailModel, hasLogoDetail } from '../../models/application-detail.model';

@Component({
  selector: 'app-application-detail',
  imports: [CommonModule],
  standalone: true,
  templateUrl: './application-detail.html',
  styleUrls: ['./application-detail.css']
})
export class ApplicationDetail implements OnInit {
  protected readonly application = signal<ApplicationDetailModel | null>(null);
  protected readonly loading = signal<boolean>(true);
  protected readonly error = signal<string | null>(null);
  protected readonly expandedVersions = new Set<number>();
  protected readonly activeEvolvumVersions = signal<any[]>([]);
  protected readonly activeCommunityVersions = signal<any[]>([]);
  protected readonly otherEvolvumVersions = signal<any[]>([]);
  protected readonly otherCommunityVersions = signal<any[]>([]);
  protected readonly activeTab = signal<'main' | 'other'>('main');
  protected readonly isFilterModalOpen = signal<boolean>(false);
  protected readonly filterState = signal<{capabilities: string[], midpointVersions: string[]}>({
    capabilities: [],
    midpointVersions: []
  });
  protected readonly openDropdown = signal<string | null>(null);
  protected readonly dropdownPosition = signal<{ top: number; left: number } | null>(null);
  protected readonly selectedFilterSection = signal<string>('capabilities');
  protected readonly applicationDownloadsCount = signal<number>(0);
  protected readonly logoLoadError = signal<boolean>(false);

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private applicationService: ApplicationService
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.loadApplication(id);
      this.loadApplicationDownloadsCount(id);
    } else {
      this.error.set('No application ID provided');
      this.loading.set(false);
    }
  }

  protected goBack(): void {
    this.router.navigate(['/applications']);
  }

  protected toggleCapabilities(versionIndex: number): void {
    if (this.expandedVersions.has(versionIndex)) {
      this.expandedVersions.delete(versionIndex);
    } else {
      this.expandedVersions.add(versionIndex);
    }
  }

  protected isExpanded(versionIndex: number): boolean {
    return this.expandedVersions.has(versionIndex);
  }

  protected filterAiGeneratedTag(tags: string[] | null): string[] {
    if (!tags) return [];
    return tags.filter(tag => {
      const normalized = tag.toLowerCase().replace(/\s+/g, '_');
      return normalized !== 'ai_generated';
    });
  }

  protected filterInstalledCapability(capabilities: string[] | null): string[] {
    if (!capabilities) return [];
    return capabilities.filter(cap => cap !== 'Installed');
  }

  protected hasNonInstalledCapabilities(capabilities: string[] | null): boolean {
    if (!capabilities) return false;
    return capabilities.some(cap => cap !== 'Installed');
  }

  protected setActiveTab(tab: 'main' | 'other'): void {
    this.activeTab.set(tab);
  }

  protected toggleFilterModal(): void {
    this.isFilterModalOpen.update(open => !open);
  }

  protected selectFilterSection(section: string): void {
    this.selectedFilterSection.set(section);
  }

  protected toggleDropdown(filterType: string, event: MouseEvent): void {
    if (this.openDropdown() === filterType) {
      this.openDropdown.set(null);
      this.dropdownPosition.set(null);
    } else {
      const target = event.currentTarget as HTMLElement;
      const rect = target.closest('.filter-chip')?.getBoundingClientRect();

      if (rect) {
        this.dropdownPosition.set({
          top: rect.bottom + 8,
          left: rect.left
        });
      }
      this.openDropdown.set(filterType);
    }
  }

  protected closeDropdown(): void {
    this.openDropdown.set(null);
    this.dropdownPosition.set(null);
  }

  protected resetFilters(): void {
    this.filterState.set({
      capabilities: [],
      midpointVersions: []
    });
    this.applyFilters();
  }

  protected clearCapabilitiesFilter(): void {
    this.filterState.update(state => ({ ...state, capabilities: [] }));
    this.applyFilters();
    this.closeDropdown();
  }

  protected clearVersionsFilter(): void {
    this.filterState.update(state => ({ ...state, midpointVersions: [] }));
    this.applyFilters();
    this.closeDropdown();
  }

  protected toggleCapabilityFilter(capability: string): void {
    const current = this.filterState().capabilities;
    if (current.includes(capability)) {
      this.filterState.update(state => ({
        ...state,
        capabilities: state.capabilities.filter(c => c !== capability)
      }));
    } else {
      this.filterState.update(state => ({
        ...state,
        capabilities: [...state.capabilities, capability]
      }));
    }
    this.applyFilters();
  }

  protected toggleMidpointVersionFilter(version: string): void {
    const current = this.filterState().midpointVersions;
    if (current.includes(version)) {
      this.filterState.update(state => ({
        ...state,
        midpointVersions: state.midpointVersions.filter(v => v !== version)
      }));
    } else {
      this.filterState.update(state => ({
        ...state,
        midpointVersions: [...state.midpointVersions, version]
      }));
    }
    this.applyFilters();
  }

  protected removeCapabilityFilter(capability: string): void {
    this.filterState.update(state => ({
      ...state,
      capabilities: state.capabilities.filter(c => c !== capability)
    }));
    this.applyFilters();
  }

  protected removeMidpointVersionFilter(version: string): void {
    this.filterState.update(state => ({
      ...state,
      midpointVersions: state.midpointVersions.filter(v => v !== version)
    }));
    this.applyFilters();
  }

  protected readonly allCapabilities = [
    'CREATE',
    'GET',
    'UPDATE',
    'DELETE',
    'TEST',
    'SCRIPT_ON_CONNECTOR',
    'SCRIPT_ON_RESOURCE',
    'AUTHENTICATION',
    'SEARCH',
    'VALIDATE',
    'SYNC',
    'LIVE_SYNC',
    'SCHEMA',
    'DISCOVER_CONFIGURATION',
    'RESOLVE_USERNAME',
    'PARTIAL_SCHEMA',
    'COMPLEX_UPDATE_DELTA',
    'UPDATE_DELTA'
  ];

  protected getAllMidpointVersions(): string[] {
    const app = this.application();
    if (!app || !app.implementationVersions) return [];

    const versionsSet = new Set<string>();
    app.implementationVersions.forEach(version => {
      if (version.midpointVersion) {
        versionsSet.add(version.midpointVersion);
      }
    });

    return Array.from(versionsSet).sort();
  }

  protected isCapabilitySelected(capability: string): boolean {
    return this.filterState().capabilities.includes(capability);
  }

  protected isMidpointVersionSelected(version: string): boolean {
    return this.filterState().midpointVersions.includes(version);
  }

  protected hasActiveFilters(): boolean {
    const filters = this.filterState();
    return filters.capabilities.length > 0 || filters.midpointVersions.length > 0;
  }

  private applyFilters(): void {
    const app = this.application();
    if (app && app.implementationVersions) {
      this.groupVersionsByLifecycleState(app.implementationVersions);
    }
  }

  protected getTotalVersionsCount(): number {
    return this.otherEvolvumVersions().length + this.otherCommunityVersions().length;
  }

  protected formatLifecycleState(state: string | null): string {
    if (!state) return '';

    switch (state) {
      case 'REQUESTED':
        return 'Requested';
      case 'ACTIVE':
        return 'Active';
      case 'WITH_ERROR':
        return 'With error';
      case 'IN_PUBLISH_PROCESS':
        return 'Publishing...';
      default:
        return state;
    }
  }

  protected getTimeSinceLastUpdate(lastModified: string | null): string {
    if (!lastModified) return 'Unknown';

    const lastModifiedDate = new Date(lastModified);
    const now = new Date();
    const diffTime = Math.abs(now.getTime() - lastModifiedDate.getTime());
    const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));
    const diffMonths = Math.floor(diffDays / 30);
    const diffYears = Math.floor(diffDays / 365);

    if (diffYears > 0) {
      return `Updated ${diffYears} year${diffYears > 1 ? 's' : ''} ago`;
    } else if (diffMonths > 0) {
      return `Updated ${diffMonths} month${diffMonths > 1 ? 's' : ''} ago`;
    } else if (diffDays > 0) {
      return `Updated ${diffDays} day${diffDays > 1 ? 's' : ''} ago`;
    } else {
      return 'Updated today';
    }
  }

  protected formatCapabilityText(text: string): string {
    if (!text) return '';

    // Replace underscores with spaces
    const withSpaces = text.replace(/_/g, ' ');

    // Convert to lowercase and capitalize first letter
    const formatted = withSpaces.toLowerCase();
    return formatted.charAt(0).toUpperCase() + formatted.slice(1);
  }

  protected formatConnectorType(framework: string | null): string {
    if (!framework) return 'Unknown';

    const normalizedFramework = framework.toUpperCase();
    if (normalizedFramework === 'CONNID') {
      return 'Java-based';
    } else if (normalizedFramework === 'SCIM_REST') {
      return 'Low-code';
    }
    return framework;
  }

  protected downloadVersion(versionId: string): void {
    this.applicationService.downloadConnector(versionId);
  }

  // ==================== Logo Methods ====================

  /**
   * Check if the current application has a logo
   */
  protected hasLogo(): boolean {
    const app = this.application();
    return app ? hasLogoDetail(app) : false;
  }

  /**
   * Get the logo URL for the current application
   */
  protected getLogoUrl(): string {
    const app = this.application();
    return app ? this.applicationService.getLogoUrl(app.id) : '';
  }

  /**
   * Handle logo load error - fallback to letter avatar
   */
  protected onLogoError(): void {
    this.logoLoadError.set(true);
  }

  /**
   * Check if should show letter avatar (no logo or logo failed to load)
   */
  protected shouldShowLetterAvatar(): boolean {
    return !this.hasLogo() || this.logoLoadError();
  }

  private loadApplication(id: string): void {
    this.applicationService.getById(id).subscribe({
      next: (data) => {
        this.application.set(data);
        this.groupVersionsByLifecycleState(data.implementationVersions);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set('Failed to load application details');
        this.loading.set(false);
        console.error('Error loading application:', err);
      }
    });
  }

  private loadApplicationDownloadsCount(applicationId: string): void {
    this.applicationService.getApplicationDownloadsCount(applicationId).subscribe({
      next: (count) => {
        this.applicationDownloadsCount.set(count);
      },
      error: (err) => {
        console.error('Error loading application downloads count:', err);
      }
    });
  }

  private groupVersionsByLifecycleState(versions: any[] | null): void {
    if (!versions) {
      this.activeEvolvumVersions.set([]);
      this.activeCommunityVersions.set([]);
      this.otherEvolvumVersions.set([]);
      this.otherCommunityVersions.set([]);
      return;
    }

    // Apply filters
    const filters = this.filterState();
    let filteredVersions = versions;

    if (filters.capabilities.length > 0) {
      filteredVersions = filteredVersions.filter(version =>
        version.capabilities && version.capabilities.some((cap: string) =>
          filters.capabilities.includes(cap)
        )
      );
    }

    if (filters.midpointVersions.length > 0) {
      filteredVersions = filteredVersions.filter(version =>
        version.midpointVersion && filters.midpointVersions.includes(version.midpointVersion)
      );
    }

    const activeEvolveum: any[] = [];
    const activeCommunity: any[] = [];
    const otherEvolveum: any[] = [];
    const otherCommunity: any[] = [];

    filteredVersions.forEach(version => {
      const isEvolveum = version.author && version.author.toLowerCase().includes('evolveum');
      const isActive = version.lifecycleState === 'ACTIVE';

      if (isActive && isEvolveum) {
        activeEvolveum.push(version);
      } else if (isActive && !isEvolveum) {
        activeCommunity.push(version);
      } else if (!isActive && isEvolveum) {
        otherEvolveum.push(version);
      } else {
        otherCommunity.push(version);
      }
    });

    this.activeEvolvumVersions.set(activeEvolveum);
    this.activeCommunityVersions.set(activeCommunity);
    this.otherEvolvumVersions.set(otherEvolveum);
    this.otherCommunityVersions.set(otherCommunity);
  }
}
