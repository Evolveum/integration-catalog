/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, OnInit, OnDestroy, signal, computed } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { ApplicationService } from '../../services/application.service';
import { ApplicationDetail as ApplicationDetailModel, hasLogoDetail } from '../../models/application-detail.model';
import { PageHeader } from '../page-header/page-header';

@Component({
  selector: 'app-application-detail',
  imports: [CommonModule, PageHeader],
  standalone: true,
  templateUrl: './application-detail.html',
  styleUrls: ['./application-detail.scss']
})
export class ApplicationDetail implements OnInit, OnDestroy {
  protected readonly application = signal<ApplicationDetailModel | null>(null);
  protected readonly loading = signal<boolean>(true);
  protected readonly error = signal<string | null>(null);
  protected readonly expandedVersions = new Set<number>();
  protected readonly expandedObjectClasses = signal<Set<string>>(new Set());
  protected readonly globalCapabilitiesExpanded = signal<boolean>(false);
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
  private activeChipElement: HTMLElement | null = null;
  private scrollListener: (() => void) | null = null;
  protected readonly selectedFilterSection = signal<string>('capabilities');
  protected readonly applicationDownloadsCount = signal<number>(0);
  protected readonly logoLoadError = signal<boolean>(false);
  protected readonly versionSearchQuery = signal<string>('');
  protected readonly isContinuePressed = signal<boolean>(true);
  protected readonly allVersions = signal<any[]>([]);
  protected readonly cancelledVersionIds = signal<string[]>([]);
  protected readonly currentPage = signal<number>(0);
  protected readonly itemsPerPage = 5;
  protected readonly totalPages = computed(() => Math.ceil(this.allVersions().length / this.itemsPerPage));
  protected readonly pagedVersions = computed(() => {
    const start = this.currentPage() * this.itemsPerPage;
    return this.allVersions().slice(start, start + this.itemsPerPage);
  });

  protected readonly methodTypeFilter = signal<string>('all');
  protected readonly methodSearchQuery = signal<string>('');

  protected readonly integrationMethods = [
    { id: 'scim',        name: 'SCIM',         description: 'Standardized provisioning' },
    { id: 'rest-api',    name: 'REST API',      description: 'Custom REST-based connector' },
    { id: 'openldap',    name: 'open LDAP',     description: 'Integration with an existing LDAP' },
    { id: 'manual-itsm', name: 'Manual ITSM',   description: 'Mighty manual' },
    { id: 'database',    name: 'Database',      description: 'The one and only' },
    { id: 'csv',         name: 'CSV',           description: 'What ever this is' },
  ];

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private applicationService: ApplicationService
  ) {}

  ngOnInit(): void {
    if (history.state?.showVersions) {
      this.isContinuePressed.set(true);
    }
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

  protected cancelVersion(id: string): void {
    this.applicationService.deleteImplementationVersion(id).subscribe({
      next: () => this.cancelledVersionIds.update(ids => [...ids, id]),
      error: () => this.cancelledVersionIds.update(ids => [...ids, id])
    });
  }

  protected cancelRequest(): void {
    const requestId = this.application()?.requestId;
    if (!requestId) return;
    this.applicationService.cancelRequest(requestId).subscribe({
      next: () => this.router.navigate(['/applications']),
      error: (err) => console.error('Failed to cancel request', err)
    });
  }

  protected isGlobalRequest(): boolean {
    const caps = this.application()?.objectClassCapabilities;
    return !!caps && caps.length > 0 && caps[0].objectName === 'global';
  }

  protected toggleObjectClass(name: string): void {
    this.expandedObjectClasses.update(set => {
      const next = new Set(set);
      if (next.has(name)) { next.delete(name); } else { next.add(name); }
      return next;
    });
  }

  protected isObjectClassExpanded(name: string): boolean {
    return this.expandedObjectClasses().has(name);
  }

  protected toggleGlobalCapabilities(): void {
    this.globalCapabilitiesExpanded.update(v => !v);
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

  protected onMethodTypeChange(event: Event): void {
    this.methodTypeFilter.set((event.target as HTMLSelectElement).value);
  }

  protected onMethodSearchChange(event: Event): void {
    this.methodSearchQuery.set((event.target as HTMLInputElement).value);
  }

  protected resetMethodFilter(): void {
    this.methodTypeFilter.set('all');
    this.methodSearchQuery.set('');
  }

  protected navigateToPublish(): void {
    const appId = this.application()?.id;
    if (appId) {
      this.router.navigate(['/publish'], { queryParams: { appId } });
    }
  }

  protected toggleFilterModal(): void {
    this.isFilterModalOpen.update(open => !open);
  }

  protected selectFilterSection(section: string): void {
    this.selectedFilterSection.set(section);
  }

  protected toggleDropdown(filterType: string, event: MouseEvent): void {
    if (this.openDropdown() === filterType) {
      this.closeDropdown();
    } else {
      const target = event.currentTarget as HTMLElement;
      const chip = target.closest('.filter-chip') as HTMLElement | null;

      if (chip) {
        this.activeChipElement = chip;
        this.updateDropdownPosition();
        this.attachScrollListener();
      }
      this.openDropdown.set(filterType);
    }
  }

  protected closeDropdown(): void {
    this.openDropdown.set(null);
    this.dropdownPosition.set(null);
    this.activeChipElement = null;
    this.detachScrollListener();
  }

  private updateDropdownPosition(): void {
    if (!this.activeChipElement) return;
    const rect = this.activeChipElement.getBoundingClientRect();
    const header = document.querySelector('app-page-header');
    const headerBottom = header ? header.getBoundingClientRect().bottom : 0;
    if (rect.top < headerBottom) {
      this.closeDropdown();
      return;
    }
    this.dropdownPosition.set({
      top: rect.bottom + 8,
      left: rect.left
    });
  }

  private attachScrollListener(): void {
    this.detachScrollListener();
    this.scrollListener = () => this.updateDropdownPosition();
    window.addEventListener('scroll', this.scrollListener, true);
  }

  private detachScrollListener(): void {
    if (this.scrollListener) {
      window.removeEventListener('scroll', this.scrollListener, true);
      this.scrollListener = null;
    }
  }

  ngOnDestroy(): void {
    this.detachScrollListener();
  }

  protected previousPage(): void {
    if (this.currentPage() > 0) this.currentPage.update(p => p - 1);
  }

  protected nextPage(): void {
    if (this.currentPage() < this.totalPages() - 1) this.currentPage.update(p => p + 1);
  }

  protected goToPage(page: number): void {
    this.currentPage.set(page);
  }

  protected resetFilters(): void {
    this.filterState.set({
      capabilities: [],
      midpointVersions: []
    });
    this.versionSearchQuery.set('');
    this.applyFilters();
  }

  protected onVersionSearchChange(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.versionSearchQuery.set(value);
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
    this.currentPage.set(0);
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
      case 'IN_REVIEW':
        return 'In review';
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

  protected getAvatarGradient(name: string): string {
    const gradients = [
      'linear-gradient(135deg, #0078d4 0%, #50e6ff 100%)',
      'linear-gradient(135deg, #7c3aed 0%, #c084fc 100%)',
      'linear-gradient(135deg, #0d9488 0%, #5eead4 100%)',
      'linear-gradient(135deg, #ea580c 0%, #fb923c 100%)',
      'linear-gradient(135deg, #be185d 0%, #f472b6 100%)',
    ];
    const index = name.split('').reduce((sum, ch) => sum + ch.charCodeAt(0), 0) % gradients.length;
    return gradients[index];
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
      this.allVersions.set([]);
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

    // Set all versions (for new unified view)
    this.allVersions.set(filteredVersions);

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
