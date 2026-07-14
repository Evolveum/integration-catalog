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
import { ApprovalConfirmModal } from '../approval-confirm-modal/approval-confirm-modal';

interface MethodGroup {
  id: string;
  name: string;
  types: string[];
  versions: IntegrationMethod[];
  publishedCount: number;
  pendingCount: number;
}

@Component({
  selector: 'app-application-detail',
  imports: [CommonModule, PageHeader, ApprovalConfirmModal],
  standalone: true,
  templateUrl: './application-detail.html',
  styleUrls: ['./application-detail.scss']
})
export class ApplicationDetail implements OnInit, OnDestroy {
  protected readonly application = signal<ApplicationDetailModel | null>(null);
  protected readonly loading = signal<boolean>(true);
  protected readonly error = signal<string | null>(null);
  // Bundle download warning toast (stays until dismissed)
  protected readonly bundleWarning = signal<string | null>(null);
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
  protected readonly itemsPerPage = 3;
  protected readonly expandedMethods = signal<Set<string>>(new Set());

  // Group the flat version list into method cards, keyed by the shared method UUID
  // (integration_method.id is stable across revisions; revision distinguishes versions).
  protected readonly groupedMethods = computed<MethodGroup[]>(() => {
    const groups = new Map<string, MethodGroup>();
    const cancelled = this.cancelledVersionIds();
    for (const v of this.allVersions()) {
      if (cancelled.includes(this.versionKey(v.id, v.revision))) continue;
      let group = groups.get(v.id);
      if (!group) {
        group = {
          id: v.id,
          name: v.displayName || v.connectorDisplayName || 'Integration method',
          types: v.integMethodTypes ?? [],
          versions: [],
          publishedCount: 0,
          pendingCount: 0
        };
        groups.set(v.id, group);
      }
      group.versions.push(v);
      if (v.lifecycleState === 'ACTIVE') group.publishedCount++;
      else if (v.lifecycleState === 'IN_REVIEW') group.pendingCount++;
    }
    const result = Array.from(groups.values());
    result.forEach(g => {
      g.versions.sort((a, b) => this.compareRevisions(a.revision, b.revision));
      // For a method with multiple versions, represent it by its OLDEST version so its
      // name/identity stays stable (matching its fixed, creation-order position).
      const oldest = g.versions[0];
      g.name = oldest.displayName || oldest.connectorDisplayName || 'Integration method';
    });
    // Unified list in creation order (map insertion order = backend created_at ASC), independent of
    // lifecycle state — so a method keeps its position even when its state changes (e.g. on approval).
    return result;
  });

  protected readonly totalPages = computed(() => Math.ceil(this.groupedMethods().length / this.itemsPerPage));
  protected readonly pagedMethods = computed(() => {
    const start = this.currentPage() * this.itemsPerPage;
    return this.groupedMethods().slice(start, start + this.itemsPerPage);
  });

  protected toggleMethod(id: string): void {
    this.expandedMethods.update(set => {
      const next = new Set(set);
      if (next.has(id)) { next.delete(id); } else { next.add(id); }
      return next;
    });
  }

  protected isMethodExpanded(id: string): boolean {
    return this.expandedMethods().has(id);
  }

  /** Derive the version badge ("v1", "v2", …) from the major part of the revision. */
  protected versionBadge(revision: string | null): string {
    const major = parseInt((revision ?? '').split('.')[0], 10);
    return isNaN(major) ? 'v1' : `v${major}`;
  }

  private compareRevisions(a: string | null, b: string | null): number {
    const pa = (a ?? '').split('.').map(n => parseInt(n, 10) || 0);
    const pb = (b ?? '').split('.').map(n => parseInt(n, 10) || 0);
    for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
      const diff = (pa[i] || 0) - (pb[i] || 0);
      if (diff !== 0) return diff;
    }
    return 0;
  }

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
      this.restoreFilters(id);
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

  protected cancelVersion(id: string, revision: string | null): void {
    this.pendingCancelType = 'version';
    this.pendingCancelVersionId = this.versionKey(id, revision);
    this.isCancelConfirmOpen.set(true);
  }

  /** Only superusers may approve (publish) an in-review revision. */
  protected isSuperuser(): boolean {
    return this.authService.currentRole() === UserRole.Superuser;
  }

  // ── Approve/Reject confirmation modal ─────────────────────────────────────
  // The version pending confirmation, and which action; the shared modal component
  // owns the two-step flow, and the actual publish/reject happens on its confirm.
  protected readonly confirmVersion = signal<IntegrationMethod | null>(null);
  protected readonly confirmMode = signal<'approve' | 'reject' | null>(null);
  protected readonly isProcessingApproval = signal<boolean>(false);
  protected readonly approvalError = signal<string>('');
  private readonly submittedDate = 'May 20, 2026';

  protected readonly confirmConnectorName = computed(() => {
    const v = this.confirmVersion();
    return v ? (v.connectorDisplayName || v.displayName || 'Integration method') : '';
  });
  protected readonly confirmVersionLabel = computed(() => this.versionBadge(this.confirmVersion()?.revision ?? ''));
  protected readonly confirmSubmittedBy = computed(() => {
    const v = this.confirmVersion();
    return v ? `${v.author || '—'} · ${this.submittedDate}` : '';
  });

  protected openApproveConfirm(version: IntegrationMethod): void {
    this.openConfirm(version, 'approve');
  }

  protected openRejectConfirm(version: IntegrationMethod): void {
    this.openConfirm(version, 'reject');
  }

  private openConfirm(version: IntegrationMethod, mode: 'approve' | 'reject'): void {
    this.approvalError.set('');
    this.confirmVersion.set(version);
    this.confirmMode.set(mode);
  }

  protected closeConfirm(): void {
    if (this.isProcessingApproval()) return;
    this.confirmMode.set(null);
    this.confirmVersion.set(null);
  }

  protected submitConfirm(): void {
    const appId = this.application()?.id;
    const version = this.confirmVersion();
    const mode = this.confirmMode();
    if (!appId || !version || !mode || this.isProcessingApproval()) return;
    this.approvalError.set('');
    this.isProcessingApproval.set(true);
    const action$ = mode === 'approve'
      ? this.applicationService.publishIntegrationMethod(appId, version.id, version.revision ?? '')
      : this.applicationService.rejectIntegrationMethod(appId, version.id, version.revision ?? '');
    action$.subscribe({
      next: () => {
        this.isProcessingApproval.set(false);
        this.confirmMode.set(null);
        this.confirmVersion.set(null);
        this.loadApplication(appId);
      },
      error: (err) => {
        console.error('Approval action failed', err);
        this.isProcessingApproval.set(false);
        const e = err as { error?: { message?: string } | string; message?: string };
        const message = (typeof e?.error === 'object' ? e.error?.message : e?.error) || e?.message;
        this.approvalError.set(message || 'The action failed. Please try again.');
      }
    });
  }

  /** Unique key for a single version (the method id is shared across revisions). */
  private versionKey(id: string, revision: string | null): string {
    return `${id}|${revision ?? ''}`;
  }

  /**
   * Unique key for a single version row's collapsible state. The method id is shared across
   * revisions, so it must be combined with the revision — otherwise expanding a section in one
   * version row expands it in every other row of the same method.
   */
  protected rowKey(version: IntegrationMethod): string {
    return this.versionKey(version.id, version.revision);
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

  /** True when the app already has at least one integration method (any lifecycle state). */
  protected hasIntegrationMethods(): boolean {
    const methods = this.application()?.integrationMethods;
    return !!methods && methods.length > 0;
  }

  protected navigateToPublish(): void {
    const appId = this.application()?.id;
    if (appId) {
      this.router.navigate(['/publish'], { queryParams: { appId } });
    }
  }

  protected navigateToEdit(versionId: string, revision: string | null): void {
    const appId = this.application()?.id;
    if (appId) {
      this.router.navigate(['/applications', appId, 'integration-method', versionId, revision ?? '', 'edit']);
    }
  }

  protected navigateToDetails(versionId: string, revision: string | null): void {
    const appId = this.application()?.id;
    if (appId) {
      this.router.navigate(['/applications', appId, 'integration-method', versionId, revision ?? '', 'details']);
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
    this.persistFilters();
  }

  private filterStorageKey(id: string): string {
    return `app-detail-filters:${id}`;
  }

  /** Persist the current filter selection so it survives navigating into an IM's details and back. */
  private persistFilters(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) return;
    const payload = {
      filterState: this.filterState(),
      methodTypeFilter: this.methodTypeFilter(),
      methodSearchQuery: this.methodSearchQuery(),
      versionSearchQuery: this.versionSearchQuery()
    };
    try {
      sessionStorage.setItem(this.filterStorageKey(id), JSON.stringify(payload));
    } catch { /* storage unavailable — ignore */ }
  }

  /** Restore a previously persisted filter selection for this application, if any. */
  private restoreFilters(id: string): void {
    try {
      const raw = sessionStorage.getItem(this.filterStorageKey(id));
      if (!raw) return;
      const saved = JSON.parse(raw);
      if (saved.filterState) this.filterState.set(saved.filterState);
      if (typeof saved.methodTypeFilter === 'string') this.methodTypeFilter.set(saved.methodTypeFilter);
      if (typeof saved.methodSearchQuery === 'string') this.methodSearchQuery.set(saved.methodSearchQuery);
      if (typeof saved.versionSearchQuery === 'string') this.versionSearchQuery.set(saved.versionSearchQuery);
    } catch { /* malformed — ignore */ }
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
      case 'REJECTED':
        return 'Rejected';
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

  protected downloadBundle(methodId: string, revision: string | null): void {
    const appId = this.application()?.id;
    if (appId) {
      this.applicationService.downloadBundle(appId, methodId, revision ?? '').subscribe({
        next: (warning) => this.bundleWarning.set(warning),
        error: () => this.bundleWarning.set('Failed to download the bundle. Please try again.')
      });
    }
  }

  protected closeBundleWarning(): void {
    this.bundleWarning.set(null);
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
        // Start with only the first method card expanded.
        const firstId = this.groupedMethods()[0]?.id;
        this.expandedMethods.set(firstId ? new Set([firstId]) : new Set());
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
      // IN_REVIEW and REJECTED share the same restricted visibility: not logged in → hidden;
      // Superuser → all; author → own; OrganizationContributor → own + same org.
      if (version.lifecycleState !== 'IN_REVIEW' && version.lifecycleState !== 'REJECTED') return true;
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
        version.displayName?.toLowerCase().includes(searchQuery) ||
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
