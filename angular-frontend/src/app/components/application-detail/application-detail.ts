/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, OnInit, OnDestroy, signal, computed } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { ApplicationService } from '../../services/application.service';
import { ApplicationDetail as ApplicationDetailModel, hasLogoDetail, IntegrationMethod, MidpointVersion, ObjectClassCapability } from '../../models/application-detail.model';
import { AuthService, UserRole } from '../../services/auth.service';
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
  protected readonly expandedGlobalCapabilities = signal<Set<string>>(new Set());
  protected readonly expandedComboSections = signal<Set<string>>(new Set());
  protected readonly globalCapabilitiesExpanded = signal<boolean>(false);
  protected readonly activeEvolvumVersions = signal<any[]>([]);
  protected readonly activeCommunityVersions = signal<any[]>([]);
  protected readonly otherEvolvumVersions = signal<any[]>([]);
  protected readonly otherCommunityVersions = signal<any[]>([]);
  protected readonly activeTab = signal<'main' | 'other'>('main');
  protected readonly isFilterModalOpen = signal<boolean>(false);
  protected readonly filterState = signal<{capabilities: string[], midpointVersions: number[]}>({
    capabilities: [],
    midpointVersions: []
  });
  protected readonly midpointVersions = signal<MidpointVersion[]>([]);
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
  protected readonly isCancelConfirmOpen = signal<boolean>(false);
  private pendingCancelType: 'request' | 'version' | null = null;
  private pendingCancelVersionId: string | null = null;
  protected readonly currentPage = signal<number>(0);
  protected readonly itemsPerPage = 5;
  protected readonly totalPages = computed(() => Math.ceil(this.allVersions().length / this.itemsPerPage));
  protected readonly pagedVersions = computed(() => {
    const start = this.currentPage() * this.itemsPerPage;
    return this.allVersions().slice(start, start + this.itemsPerPage);
  });

  protected readonly methodTypeFilter = signal<string>('all');
  protected readonly methodSearchQuery = signal<string>('');
  protected readonly methodDropdownOpen = signal<boolean>(false);

  protected readonly availableMethodTypes = signal<{ id: number; displayName: string }[]>([]);

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private applicationService: ApplicationService,
    private authService: AuthService
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
    this.applicationService.getIntegrationMethodTypes().subscribe({
      next: (types) => this.availableMethodTypes.set(types),
      error: (err) => console.error('Failed to load integration method types', err)
    });
    this.applicationService.getMidpointVersions().subscribe({
      next: (versions) => this.midpointVersions.set(versions),
      error: (err) => console.error('Failed to load MidPoint versions', err)
    });
  }

  protected goBack(): void {
    this.router.navigate(['/applications']);
  }

  protected cancelVersion(id: string): void {
    this.pendingCancelType = 'version';
    this.pendingCancelVersionId = id;
    this.isCancelConfirmOpen.set(true);
  }

  protected cancelRequest(): void {
    this.pendingCancelType = 'request';
    this.pendingCancelVersionId = null;
    this.isCancelConfirmOpen.set(true);
  }

  protected closeCancelConfirm(): void {
    this.isCancelConfirmOpen.set(false);
    this.pendingCancelType = null;
    this.pendingCancelVersionId = null;
  }

  protected confirmCancel(): void {
    if (this.pendingCancelType === 'version' && this.pendingCancelVersionId) {
      this.cancelledVersionIds.update(ids => [...ids, this.pendingCancelVersionId!]);
      this.closeCancelConfirm();
    } else if (this.pendingCancelType === 'request') {
      const requestId = this.application()?.requestId;
      if (!requestId) { this.closeCancelConfirm(); return; }
      this.applicationService.cancelRequest(requestId).subscribe({
        next: () => { this.closeCancelConfirm(); this.router.navigate(['/applications']); },
        error: (err) => { console.error('Failed to cancel request', err); this.closeCancelConfirm(); }
      });
    }
  }

  protected isGlobalRequest(): boolean {
    const caps = this.application()?.objectClassCapabilities;
    return !!caps && caps.length > 0 && caps.every(c => c.objectName.toLowerCase() === 'global');
  }

  protected isGlobalMethod(version: IntegrationMethod): boolean {
    return !!version.objectClassCapabilities &&
      version.objectClassCapabilities.length > 0 &&
      version.objectClassCapabilities.every(c => c.objectName.toLowerCase() === 'global');
  }

  protected isCombinedRequest(): boolean {
    const caps = this.application()?.objectClassCapabilities;
    if (!caps || caps.length === 0) return false;
    return caps.some(c => c.objectName.toLowerCase() === 'global') &&
           caps.some(c => c.objectName.toLowerCase() !== 'global');
  }

  protected isCombinedMethod(version: IntegrationMethod): boolean {
    const caps = version.objectClassCapabilities;
    if (!caps || caps.length === 0) return false;
    return caps.some(c => c.objectName.toLowerCase() === 'global') &&
           caps.some(c => c.objectName.toLowerCase() !== 'global');
  }

  protected toggleComboSection(key: string): void {
    this.expandedComboSections.update(set => {
      const next = new Set(set);
      if (next.has(key)) { next.delete(key); } else { next.add(key); }
      return next;
    });
  }

  protected isComboSectionExpanded(key: string): boolean {
    return this.expandedComboSections().has(key);
  }

  protected getGlobalCaps(version: IntegrationMethod): string[] {
    return version.objectClassCapabilities?.find(c => c.objectName.toLowerCase() === 'global')?.capabilities ?? [];
  }

  protected getSpecificOccs(version: IntegrationMethod): ObjectClassCapability[] {
    return version.objectClassCapabilities?.filter(c => c.objectName.toLowerCase() !== 'global') ?? [];
  }

  protected getRequestGlobalCaps(): string[] {
    return this.application()?.objectClassCapabilities?.find(c => c.objectName.toLowerCase() === 'global')?.capabilities ?? [];
  }

  protected getRequestSpecificOccs(): ObjectClassCapability[] {
    return this.application()?.objectClassCapabilities?.filter(c => c.objectName.toLowerCase() !== 'global') ?? [];
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

  protected toggleMethodGlobalCapabilities(versionId: string): void {
    this.expandedGlobalCapabilities.update(set => {
      const next = new Set(set);
      if (next.has(versionId)) { next.delete(versionId); } else { next.add(versionId); }
      return next;
    });
  }

  protected isMethodGlobalCapabilitiesExpanded(versionId: string): boolean {
    return this.expandedGlobalCapabilities().has(versionId);
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
    this.applyFilters();
  }

  protected toggleMethodDropdown(): void {
    this.methodDropdownOpen.update(v => !v);
  }

  protected selectMethodType(value: string): void {
    this.methodTypeFilter.set(value);
    this.methodDropdownOpen.set(false);
    this.applyFilters();
  }

  protected selectedMethodTypeLabel(): string {
    const current = this.methodTypeFilter();
    if (current === 'all') return 'All';
    return current;
  }

  protected onMethodSearchChange(event: Event): void {
    this.methodSearchQuery.set((event.target as HTMLInputElement).value);
    this.applyFilters();
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

  protected navigateToEdit(versionId: string, revision: string): void {
    const appId = this.application()?.id;
    if (appId) {
      this.router.navigate(['/applications', appId, 'integration-method', versionId, revision, 'edit']);
    }
  }

  protected navigateToDetails(versionId: string, revision: string): void {
    const appId = this.application()?.id;
    if (appId) {
      this.router.navigate(['/applications', appId, 'integration-method', versionId, revision, 'details']);
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
    this.filterState.set({ capabilities: [], midpointVersions: [] });
    this.methodTypeFilter.set('all');
    this.methodDropdownOpen.set(false);
    this.versionSearchQuery.set('');
    this.methodSearchQuery.set('');
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

  protected toggleMidpointVersionFilter(versionId: number): void {
    const current = this.filterState().midpointVersions;
    if (current.includes(versionId)) {
      this.filterState.update(state => ({
        ...state,
        midpointVersions: state.midpointVersions.filter(v => v !== versionId)
      }));
    } else {
      this.filterState.update(state => ({
        ...state,
        midpointVersions: [...state.midpointVersions, versionId]
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

  protected removeMidpointVersionFilter(versionId: number): void {
    this.filterState.update(state => ({
      ...state,
      midpointVersions: state.midpointVersions.filter(v => v !== versionId)
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

  protected getMidpointVersionLabel(id: number): string {
    const v = this.midpointVersions().find(v => v.id === id);
    return v ? `${v.version} — ${v.versionName}` : String(id);
  }

  protected isCapabilitySelected(capability: string): boolean {
    return this.filterState().capabilities.includes(capability);
  }

  protected isMidpointVersionSelected(versionId: number): boolean {
    return this.filterState().midpointVersions.includes(versionId);
  }

  protected hasActiveFilters(): boolean {
    const filters = this.filterState();
    return filters.capabilities.length > 0 || filters.midpointVersions.length > 0;
  }

  private applyFilters(): void {
    this.currentPage.set(0);
    const app = this.application();
    if (app && app.integrationMethods) {
      this.groupVersionsByLifecycleState(app.integrationMethods);
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

  protected downloadBundle(methodId: string, revision: string): void {
    const appId = this.application()?.id;
    if (appId) {
      this.applicationService.downloadBundle(appId, methodId, revision);
    }
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
        this.groupVersionsByLifecycleState(data.integrationMethods);
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

    // Filter IN_REVIEW visibility: not logged in → hide all; Superuser → see all;
    // OrganizationContributor → own + same org; others → own only
    const currentUser = this.authService.currentUser();
    const isLoggedIn = this.authService.isLoggedIn();
    const role = this.authService.currentRole();
    const isSuperuser = role === UserRole.Superuser;
    const isOrgContributor = role === UserRole.OrganizationContributor;
    const currentOrgId = this.authService.currentOrganizationId();

    let filteredVersions = versions.filter(version => {
      if (version.lifecycleState !== 'IN_REVIEW') return true;
      if (!isLoggedIn) return false;
      if (isSuperuser) return true;
      if (version.author === currentUser) return true;
      if (isOrgContributor && currentOrgId !== null && version.organizationId === currentOrgId) return true;
      return false;
    });

    // Apply filters
    const filters = this.filterState();

    if (filters.capabilities.length > 0) {
      filteredVersions = filteredVersions.filter(version =>
        version.capabilities && version.capabilities.some((cap: string) =>
          filters.capabilities.includes(cap)
        )
      );
    }

    if (filters.midpointVersions.length > 0) {
      filteredVersions = filteredVersions.filter(method =>
        filters.midpointVersions.some(selectedId => {
          const min = method.midpointMinVersionId;
          const max = method.midpointMaxVersionId;
          return (min === null || min <= selectedId) && (max === null || max >= selectedId);
        })
      );
    }

    const methodType = this.methodTypeFilter();
    if (methodType !== 'all') {
      filteredVersions = filteredVersions.filter(version =>
        version.integMethodTypes && version.integMethodTypes.includes(methodType)
      );
    }

    const searchQuery = this.methodSearchQuery().toLowerCase().trim();
    if (searchQuery) {
      filteredVersions = filteredVersions.filter(version =>
        version.connectorDisplayName?.toLowerCase().includes(searchQuery) ||
        version.integMethodTypes?.some((t: string) => t.toLowerCase().includes(searchQuery)) ||
        version.description?.toLowerCase().includes(searchQuery)
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
