/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, OnInit, OnDestroy, signal, computed, ViewChild, ViewChildren, ElementRef, AfterViewInit, QueryList } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApplicationService } from '../../services/application.service';
import { ApplicationsListStateService } from '../../services/applications-list-state.service';
import { Application, ApplicationTag, hasLogo } from '../../models/application.model';
import { CategoryCount } from '../../models/category-count.model';
import { RequestForm } from '../request-form/request-form';
import { FilterModal, FilterState } from '../filter-modal/filter-modal';
import { AuthService } from '../../services/auth.service';
import { PageHeader } from '../page-header/page-header';
import { ToastService } from '../../services/toast.service';

@Component({
  selector: 'app-applications-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RequestForm, FilterModal, PageHeader],
  templateUrl: './applications-list.html',
  styleUrls: ['./applications-list.scss']
})
export class ApplicationsList implements OnInit, AfterViewInit, OnDestroy {
  @ViewChild('scrollContainer') scrollContainer!: ElementRef<HTMLDivElement>;
  @ViewChild('scrollContainerMore') scrollContainerMore!: ElementRef<HTMLDivElement>;
  @ViewChildren('featuredCard') featuredCards!: QueryList<ElementRef<HTMLDivElement>>;

  protected applications = signal<Application[]>([]);
  protected readonly categories = signal<CategoryCount[]>([]);
  protected readonly totalDownloadsCount = signal<number>(0);

  // Loaded from the capability table so the dropdown matches the filter modal.
  protected readonly allCapabilities = signal<string[]>([]);

  protected readonly allAppStatuses = [
    'ACTIVE',
    'REQUESTED',
    'WITH_ERROR',
    'IN_REVIEW'
  ];
  protected readonly loading = signal<boolean>(true);
  protected readonly error = signal<string | null>(null);
  protected readonly searchQuery = signal('');
  protected readonly canScrollLeft = signal<boolean>(false);
  protected readonly canScrollRight = signal<boolean>(false);
  protected readonly currentPage = signal<number>(0);
  protected readonly itemsPerPage = 12;
  protected readonly sortBy = signal<'alphabetical' | 'popularity' | 'activity'>('alphabetical');
  protected readonly activeTab = signal<string>('all');
  protected isRequestModalOpen = signal<boolean>(false);

  protected isFilterModalOpen = signal<boolean>(false);
  protected openDropdown = signal<string | null>(null);
  protected showLoginRequiredMessage = signal<boolean>(false);
  protected showPermissionDeniedMessage = signal<boolean>(false);
  protected dropdownPosition = signal<{ top: number; left: number } | null>(null);

  private activeChipElement: HTMLElement | null = null;
  private scrollListener: (() => void) | null = null;

  protected filterState = signal<FilterState>({
    trending: false,
    categories: [],
    deploymentTypes: [],
    capabilities: [],
    appStatus: [],
    midpointVersions: [],
    integrationMethods: [],
    maintainers: []
  });

  // Chips currently shown. A chip is pinned here when its filter is first
  // applied and is removed ONLY via the chip "X" (removeChip). Clearing or
  // deselecting a filter empties its selections but keeps the chip pinned.
  protected readonly visibleChips = signal<Set<string>>(new Set());

  // All defined application tags, so the chip dropdowns list every category /
  // deployment type (including unused ones) — matching the filter modal.
  private readonly allTags = signal<ApplicationTag[]>([]);

  protected readonly currentUser = computed(() => this.authService.currentUser());
  protected readonly canVote = () => this.authService.canVote();
  protected readonly canRequest = () => this.authService.canRequest();
  protected readonly canUpload = () => this.authService.canUpload();

  protected readonly featuredApplications = computed(() => {
    const query = this.searchQuery().toLowerCase().trim();
    const activeTab = this.activeTab();
    const filters = this.filterState();
    const apps = this.applications();

    // Don't show featured apps when searching or filtering
    const hasActiveFilters = filters.trending ||
                            filters.categories.length > 0 ||
                            filters.deploymentTypes.length > 0 ||
                            filters.capabilities.length > 0 ||
                            filters.appStatus.length > 0 ||
                            filters.midpointVersions.length > 0 ||
                            filters.integrationMethods.length > 0 ||
                            filters.maintainers.length > 0;

    if (query || activeTab !== 'all' || hasActiveFilters) {
      return [];
    }
    return apps;
  });

  protected readonly moreApplications = computed(() => {
    const query = this.searchQuery().toLowerCase().trim();
    const activeTab = this.activeTab();
    const filters = this.filterState();

    // Apply shared filtering logic
    let apps = this.applyFilters([...this.applications()], query, activeTab, filters);

    // Sort based on selected option
    const sortOption = this.sortBy();
    if (sortOption === 'alphabetical') {
      apps.sort((a, b) => a.displayName.localeCompare(b.displayName));
    } else if (sortOption === 'popularity') {
      apps.sort((a, b) => {
        const aIsPopular = a.tags?.some(tag => tag.name === 'popular') ? 1 : 0;
        const bIsPopular = b.tags?.some(tag => tag.name === 'popular') ? 1 : 0;
        return bIsPopular - aIsPopular;
      });
    } else if (sortOption === 'activity') {
      apps.sort((a, b) => {
        const aIsActive = a.lifecycleState === 'ACTIVE' ? 1 : 0;
        const bIsActive = b.lifecycleState === 'ACTIVE' ? 1 : 0;
        return bIsActive - aIsActive;
      });
    }

    const start = this.currentPage() * this.itemsPerPage;
    const end = start + this.itemsPerPage;
    return apps.slice(start, end);
  });

  protected readonly activeIntegrationsCount = computed(() => {
    return this.applications().filter(app => app.lifecycleState === 'ACTIVE').length;
  });

  protected readonly filteredCount = computed(() => {
    const query = this.searchQuery().toLowerCase().trim();
    const activeTab = this.activeTab();
    const filters = this.filterState();

    return this.applyFilters(this.applications(), query, activeTab, filters).length;
  });

  /**
   * Applies all filters to the applications list.
   * Shared by moreApplications and filteredCount computed signals.
   */
  private applyFilters(apps: Application[], query: string, activeTab: string, filters: FilterState): Application[] {
    let filtered = apps;

    // Filter by category tab (if not 'all')
    if (activeTab !== 'all') {
      filtered = filtered.filter(app =>
        app.categories?.some((category: ApplicationTag) => category.displayName === activeTab)
      );
    }

    // Filter by search query
    if (query) {
      filtered = filtered.filter(app =>
        app.displayName.toLowerCase().includes(query)
      );
    }

    // Apply advanced filters
    if (filters.trending) {
      filtered = filtered.filter(app => app.tags?.some(tag => tag.name === 'popular'));
    }

    if (filters.categories.length > 0) {
      filtered = filtered.filter(app => {
        const allTags = [...(app.categories || []), ...(app.tags || [])];
        return filters.categories.every(selectedCat =>
          allTags.some(tag => tag.name === selectedCat && tag.tagType === 'CATEGORY')
        );
      });
    }

    if (filters.deploymentTypes.length > 0) {
      filtered = filtered.filter(app => {
        const allTags = [...(app.categories || []), ...(app.tags || [])];
        return filters.deploymentTypes.every(selectedDep =>
          allTags.some(tag => tag.name === selectedDep && tag.tagType === 'DEPLOYMENT')
        );
      });
    }

    if (filters.capabilities.length > 0) {
      filtered = filtered.filter(app =>
        filters.capabilities.every((capability: string) =>
          app.capabilities?.includes(capability)
        )
      );
    }

    if (filters.appStatus.length > 0) {
      filtered = filtered.filter(app =>
        app.lifecycleState && filters.appStatus.includes(app.lifecycleState)
      );
    }

    if (filters.midpointVersions.length > 0) {
      // app.midpointVersions holds the version ids covered by its methods' ranges;
      // match apps supporting ANY of the selected versions.
      filtered = filtered.filter(app =>
        filters.midpointVersions.some((versionId: number) =>
          app.midpointVersions?.includes(String(versionId))
        )
      );
    }

    if (filters.integrationMethods.length > 0) {
      // Match apps offering ANY of the selected integration methods.
      filtered = filtered.filter(app =>
        filters.integrationMethods.some((method: string) =>
          app.integrationMethodTypes?.includes(method)
        )
      );
    }

    if (filters.maintainers.length > 0) {
      // Match apps maintained by ANY of the selected maintainer categories.
      filtered = filtered.filter(app =>
        filters.maintainers.some((maintainer: string) =>
          app.maintainers?.includes(maintainer)
        )
      );
    }

    return filtered;
  }

  protected readonly totalPages = computed(() => {
    return Math.ceil(this.filteredCount() / this.itemsPerPage);
  });

  constructor(
    private applicationService: ApplicationService,
    private router: Router,
    protected authService: AuthService,
    private listState: ApplicationsListStateService,
    private toastService: ToastService
  ) {}

  ngOnInit(): void {
    this.restoreViewState();
    this.loadApplications();
    this.loadCategories();
    this.loadTotalDownloadsCount();
    this.applicationService.getMidpointVersions().subscribe({
      next: (versions) => this.allMidpointVersions.set(versions),
      error: (err) => console.error('Failed to load MidPoint versions', err)
    });
    this.applicationService.getIntegrationMethodTypes().subscribe({
      next: (types) => this.allIntegrationMethods.set(types.map(t => t.displayName)),
      error: (err) => console.error('Failed to load integration methods', err)
    });
    this.applicationService.getAllTags().subscribe({
      next: (tags) => this.allTags.set(tags),
      error: (err) => console.error('Failed to load application tags', err)
    });
    this.applicationService.getCapabilities().subscribe({
      next: (caps) => this.allCapabilities.set(
        [...caps]
          .sort((a, b) => (a.displayOrder ?? Number.MAX_SAFE_INTEGER) - (b.displayOrder ?? Number.MAX_SAFE_INTEGER))
          .map(c => c.name)
      ),
      error: (err) => console.error('Failed to load capabilities', err)
    });
  }

  ngAfterViewInit(): void {
    this.updateScrollButtons();
    setTimeout(() => {
      this.updateCardOpacities();
    }, 0);
  }

  protected onSearchChange(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.searchQuery.set(value);
    this.currentPage.set(0);
  }

  protected resetFilter(): void {
    this.searchQuery.set('');
    this.filterState.set({
      trending: false,
      categories: [],
      deploymentTypes: [],
      capabilities: [],
      appStatus: [],
      midpointVersions: [],
      integrationMethods: [],
      maintainers: []
    });
    this.visibleChips.set(new Set());
    this.currentPage.set(0);
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
    this.saveViewState();
  }

  /** Restore filters/search/paging saved before navigating away (e.g. to app detail). */
  private restoreViewState(): void {
    const saved = this.listState.restore();
    if (!saved) return;
    this.filterState.set(saved.filterState);
    this.visibleChips.set(new Set(saved.visibleChips));
    this.searchQuery.set(saved.searchQuery);
    this.currentPage.set(saved.currentPage);
    this.sortBy.set(saved.sortBy);
    this.activeTab.set(saved.activeTab);
  }

  private saveViewState(): void {
    this.listState.save({
      filterState: this.filterState(),
      visibleChips: Array.from(this.visibleChips()),
      searchQuery: this.searchQuery(),
      currentPage: this.currentPage(),
      sortBy: this.sortBy(),
      activeTab: this.activeTab()
    });
  }

  // "Clear filter" link: clears the selections and closes the popover, but the
  // chip itself stays (only the chip "X" / removeChip removes it).
  protected clearTrendingFilter(): void {
    this.filterState.update(state => ({ ...state, trending: false }));
    this.currentPage.set(0);
    this.closeDropdown();
  }

  protected clearCategoriesFilter(): void {
    this.filterState.update(state => ({ ...state, categories: [] }));
    this.currentPage.set(0);
    this.closeDropdown();
  }

  protected clearDeploymentTypesFilter(): void {
    this.filterState.update(state => ({ ...state, deploymentTypes: [] }));
    this.currentPage.set(0);
    this.closeDropdown();
  }

  protected clearCapabilitiesFilter(): void {
    this.filterState.update(state => ({ ...state, capabilities: [] }));
    this.currentPage.set(0);
    this.closeDropdown();
  }

  protected clearAppStatusFilter(): void {
    this.filterState.update(state => ({ ...state, appStatus: [] }));
    this.currentPage.set(0);
    this.closeDropdown();
  }

  protected clearMidpointVersionsFilter(): void {
    this.filterState.update(state => ({ ...state, midpointVersions: [] }));
    this.currentPage.set(0);
    this.closeDropdown();
  }

  protected clearIntegrationMethodsFilter(): void {
    this.filterState.update(state => ({ ...state, integrationMethods: [] }));
    this.currentPage.set(0);
    this.closeDropdown();
  }

  protected clearMaintainersFilter(): void {
    this.filterState.update(state => ({ ...state, maintainers: [] }));
    this.currentPage.set(0);
    this.closeDropdown();
  }

  /**
   * Chip "X": removes the chip entirely. This is the ONLY way a chip disappears —
   * clearing selections or deselecting everything keeps the chip visible.
   */
  protected removeChip(type: string): void {
    switch (type) {
      case 'trending': this.clearTrendingFilter(); break;
      case 'categories': this.clearCategoriesFilter(); break;
      case 'deploymentTypes': this.clearDeploymentTypesFilter(); break;
      case 'capabilities': this.clearCapabilitiesFilter(); break;
      case 'appStatus': this.clearAppStatusFilter(); break;
      case 'midpointVersions': this.clearMidpointVersionsFilter(); break;
      case 'integrationMethods': this.clearIntegrationMethodsFilter(); break;
      case 'maintainers': this.clearMaintainersFilter(); break;
    }
    this.visibleChips.update(set => {
      const next = new Set(set);
      next.delete(type);
      return next;
    });
  }

  protected removeCategoryFilter(category: string): void {
    this.filterState.update(state => ({
      ...state,
      categories: state.categories.filter(c => c !== category)
    }));
    this.currentPage.set(0);
  }

  protected removeDeploymentTypeFilter(deployment: string): void {
    this.filterState.update(state => ({
      ...state,
      deploymentTypes: state.deploymentTypes.filter(d => d !== deployment)
    }));
    this.currentPage.set(0);
  }

  protected removeCapabilityFilter(capability: string): void {
    this.filterState.update(state => ({
      ...state,
      capabilities: state.capabilities.filter(c => c !== capability)
    }));
    this.currentPage.set(0);
  }

  protected removeAppStatusFilter(status: string): void {
    this.filterState.update(state => ({
      ...state,
      appStatus: state.appStatus.filter(s => s !== status)
    }));
    this.currentPage.set(0);
  }

  protected removeMidpointVersionFilter(versionId: number): void {
    this.filterState.update(state => ({
      ...state,
      midpointVersions: state.midpointVersions.filter(v => v !== versionId)
    }));
    this.currentPage.set(0);
  }

  protected toggleCategoryInFilter(category: string): void {
    const categories = this.filterState().categories;
    if (categories.includes(category)) {
      this.removeCategoryFilter(category);
    } else {
      this.filterState.update(state => ({
        ...state,
        categories: [...state.categories, category]
      }));
      this.currentPage.set(0);
    }
  }

  protected toggleDeploymentTypeInFilter(deployment: string): void {
    const deploymentTypes = this.filterState().deploymentTypes;
    if (deploymentTypes.includes(deployment)) {
      this.removeDeploymentTypeFilter(deployment);
    } else {
      this.filterState.update(state => ({
        ...state,
        deploymentTypes: [...state.deploymentTypes, deployment]
      }));
      this.currentPage.set(0);
    }
  }

  protected toggleCapabilityInFilter(capability: string): void {
    const capabilities = this.filterState().capabilities;
    if (capabilities.includes(capability)) {
      this.removeCapabilityFilter(capability);
    } else {
      this.filterState.update(state => ({
        ...state,
        capabilities: [...state.capabilities, capability]
      }));
      this.currentPage.set(0);
    }
  }

  protected toggleAppStatusInFilter(status: string): void {
    const statuses = this.filterState().appStatus;
    if (statuses.includes(status)) {
      this.removeAppStatusFilter(status);
    } else {
      this.filterState.update(state => ({
        ...state,
        appStatus: [...state.appStatus, status]
      }));
      this.currentPage.set(0);
    }
  }

  protected toggleMidpointVersionInFilter(versionId: number): void {
    const versions = this.filterState().midpointVersions;
    if (versions.includes(versionId)) {
      this.removeMidpointVersionFilter(versionId);
    } else {
      this.filterState.update(state => ({
        ...state,
        midpointVersions: [...state.midpointVersions, versionId]
      }));
      this.currentPage.set(0);
    }
  }

  protected toggleIntegrationMethodInFilter(method: string): void {
    const methods = this.filterState().integrationMethods;
    if (methods.includes(method)) {
      this.filterState.update(state => ({
        ...state,
        integrationMethods: state.integrationMethods.filter(m => m !== method)
      }));
    } else {
      this.filterState.update(state => ({
        ...state,
        integrationMethods: [...state.integrationMethods, method]
      }));
    }
    this.currentPage.set(0);
  }

  protected toggleMaintainerInFilter(maintainer: string): void {
    const maintainers = this.filterState().maintainers;
    if (maintainers.includes(maintainer)) {
      this.filterState.update(state => ({
        ...state,
        maintainers: state.maintainers.filter(m => m !== maintainer)
      }));
    } else {
      this.filterState.update(state => ({
        ...state,
        maintainers: [...state.maintainers, maintainer]
      }));
    }
    this.currentPage.set(0);
  }

  protected getFilteredCountForCategory(category: string): number {
    const apps = this.applications();
    return apps.filter(app => {
      const allTags = [...(app.categories || []), ...(app.tags || [])];
      return allTags.some(tag =>
        tag.name === category && (tag.tagType === 'CATEGORY' || tag.tagType === 'DEPLOYMENT')
      );
    }).length;
  }

  protected getFilteredCountForCapability(capability: string): number {
    const apps = this.applications();
    return apps.filter(app =>
      app.capabilities?.includes(capability)
    ).length;
  }

  protected getFilteredCountForAppStatus(status: string): number {
    const apps = this.applications();
    return apps.filter(app =>
      app.lifecycleState === status
    ).length;
  }

  protected getTrendingCount(): number {
    const apps = this.applications();
    return apps.filter(app =>
      app.tags?.some(tag => tag.name === 'popular')
    ).length;
  }

  protected formatCapability(capability: string): string {
    return capability
      .split('_')
      .map(word => word.charAt(0) + word.slice(1).toLowerCase())
      .join(' ');
  }

  protected formatAppStatus(status: string): string {
    return this.formatLifecycleState(status);
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

  protected getCategoryDisplayName(categoryName: string): string {
    // Find the display name by looking through all applications' tags
    for (const app of this.applications()) {
      const allTags = [...(app.categories || []), ...(app.tags || [])];
      const tag = allTags.find(t => t.name === categoryName);
      if (tag) {
        return tag.displayName;
      }
    }
    return categoryName;
  }

  protected getAllAvailableCategories(): Array<{name: string, displayName: string}> {
    return this.collectTagsByType('CATEGORY');
  }

  protected getAllAvailableDeploymentTypes(): Array<{name: string, displayName: string}> {
    return this.collectTagsByType('DEPLOYMENT');
  }

  private collectTagsByType(tagType: string): Array<{name: string, displayName: string}> {
    const map = new Map<string, string>();
    // All defined tags of this type (so unused ones still appear), like the modal.
    for (const tag of this.allTags()) {
      if (tag.tagType === tagType) {
        map.set(tag.name, tag.displayName);
      }
    }
    // Plus any present on apps but missing from the tag list.
    for (const app of this.applications()) {
      const appTags = [...(app.categories || []), ...(app.tags || [])];
      for (const tag of appTags) {
        if (tag.tagType === tagType) {
          map.set(tag.name, tag.displayName);
        }
      }
    }
    return Array.from(map.entries())
      .map(([name, displayName]) => ({ name, displayName }))
      .sort((a, b) => a.displayName.localeCompare(b.displayName));
  }

  protected isCategorySelected(category: string): boolean {
    return this.filterState().categories.includes(category);
  }

  protected isDeploymentTypeSelected(deployment: string): boolean {
    return this.filterState().deploymentTypes.includes(deployment);
  }

  protected isCapabilitySelected(capability: string): boolean {
    return this.filterState().capabilities.includes(capability);
  }

  protected isAppStatusSelected(status: string): boolean {
    return this.filterState().appStatus.includes(status);
  }

  protected isMidpointVersionSelected(versionId: number): boolean {
    return this.filterState().midpointVersions.includes(versionId);
  }

  protected isIntegrationMethodSelected(method: string): boolean {
    return this.filterState().integrationMethods.includes(method);
  }

  protected isMaintainerSelected(maintainer: string): boolean {
    return this.filterState().maintainers.includes(maintainer);
  }

  protected readonly allMidpointVersions = signal<{ id: number; version: string; versionName: string }[]>([]);

  // Loaded from the backend so the dropdown matches the filter modal's full list.
  protected readonly allIntegrationMethods = signal<string[]>([]);

  protected readonly allMaintainers: string[] = [
    'Evolveum',
    'Community',
    'Partner'
  ];

  protected onSortChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value as 'alphabetical' | 'popularity' | 'activity';
    this.sortBy.set(value);
    this.currentPage.set(0);
  }

  protected setActiveTab(tab: string): void {
    this.activeTab.set(tab);
    this.currentPage.set(0);
  }

  protected scrollLeft(): void {
    const container = this.scrollContainer.nativeElement;
    container.scrollBy({ left: -300, behavior: 'smooth' });
  }

  protected scrollRight(): void {
    const container = this.scrollContainer.nativeElement;
    container.scrollBy({ left: 300, behavior: 'smooth' });
  }

  protected nextPage(): void {
    if (this.currentPage() < this.totalPages() - 1) {
      this.currentPage.update(p => p + 1);
    }
  }

  protected previousPage(): void {
    if (this.currentPage() > 0) {
      this.currentPage.update(p => p - 1);
    }
  }

  protected goToPage(page: number): void {
    this.currentPage.set(page);
  }

  protected onScroll(): void {
    this.updateScrollButtons();
    this.updateCardOpacities();
  }

  private updateScrollButtons(): void {
    const container = this.scrollContainer?.nativeElement;
    if (!container) return;

    this.canScrollLeft.set(container.scrollLeft > 0);
    this.canScrollRight.set(
      container.scrollLeft < container.scrollWidth - container.clientWidth - 1
    );
  }

  private updateCardOpacities(): void {
    const container = this.scrollContainer?.nativeElement;
    if (!container || !this.featuredCards) return;

    const containerRect = container.getBoundingClientRect();
    const containerLeft = containerRect.left;
    const containerRight = containerRect.right;

    this.featuredCards.forEach(cardRef => {
      const card = cardRef.nativeElement;
      const cardRect = card.getBoundingClientRect();
      const cardLeft = cardRect.left;
      const cardRight = cardRect.right;

      // Calculate how much of the card is visible
      const visibleLeft = Math.max(cardLeft, containerLeft);
      const visibleRight = Math.min(cardRight, containerRight);
      const visibleWidth = Math.max(0, visibleRight - visibleLeft);
      const cardWidth = cardRect.width;
      const visibilityRatio = visibleWidth / cardWidth;

      // If card is fully visible (or almost fully), opacity is 1, otherwise 0.5
      if (visibilityRatio >= 0.99) {
        card.style.opacity = '1';
      } else {
        card.style.opacity = '0.5';
      }
    });
  }

  protected navigateToDetail(id: string): void {
    this.router.navigate(['/applications', id]);
  }

  protected openRequestModal(): void {
    if (!this.authService.canRequest()) {
      this.showLoginRequiredMessage.set(true);
      setTimeout(() => this.showLoginRequiredMessage.set(false), 5000);
      this.authService.openLoginModal();
      return;
    }
    this.isRequestModalOpen.set(true);
  }

  protected closeLoginRequiredMessage(): void {
    this.showLoginRequiredMessage.set(false);
  }

  protected closePermissionDeniedMessage(): void {
    this.showPermissionDeniedMessage.set(false);
  }

  protected closeRequestModal(): void {
    this.isRequestModalOpen.set(false);
  }

  protected openUploadModal(): void {
    if (!this.authService.canUpload()) {
      if (!this.authService.isLoggedIn()) {
        this.showLoginRequiredMessage.set(true);
        setTimeout(() => this.showLoginRequiredMessage.set(false), 5000);
        this.authService.openLoginModal();
      } else {
        this.showPermissionDeniedMessage.set(true);
        setTimeout(() => this.showPermissionDeniedMessage.set(false), 5000);
      }
      return;
    }
    this.router.navigate(['/publish']);
  }

  protected reloadApplications(): void {
    this.loading.set(true);
    this.loadApplications();
  }

  protected openFilterModal(): void {
    this.isFilterModalOpen.set(true);
  }

  protected closeFilterModal(): void {
    this.isFilterModalOpen.set(false);
  }

  protected applyFilter(filterState: FilterState): void {
    this.filterState.set(filterState);
    this.currentPage.set(0);
    // Pin a chip for every filter that has a selection. Add-only: chips already
    // pinned stay pinned (removed only via the chip "X").
    this.visibleChips.update(set => {
      const next = new Set(set);
      if (filterState.categories.length) next.add('categories');
      if (filterState.deploymentTypes.length) next.add('deploymentTypes');
      if (filterState.capabilities.length) next.add('capabilities');
      if (filterState.appStatus.length) next.add('appStatus');
      if (filterState.midpointVersions.length) next.add('midpointVersions');
      if (filterState.integrationMethods.length) next.add('integrationMethods');
      if (filterState.maintainers.length) next.add('maintainers');
      return next;
    });
  }

  protected voteForRequest(app: Application): void {
    const currentUser = this.currentUser();

    if (!currentUser || !this.authService.canVote()) {
      alert('Please log in to vote');
      return;
    }

    if (!app.requestId) {
      alert('Request ID not found');
      return;
    }

    this.applicationService.submitVote(app.requestId, currentUser).subscribe({
      next: () => {
        // Increment vote count locally
        app.voteCount = (app.voteCount || 0) + 1;
      },
      error: (err) => {
        if (err.status === 400) {
          alert('You have already voted for this request');
        } else {
          alert('Failed to submit vote. Please try again.');
        }
        console.error('Error submitting vote:', err);
      }
    });
  }

  protected isPopular(app: Application): boolean {
    return app.tags?.some(tag => tag.name === 'popular' || tag.name === 'POPULAR') ||
           app.categories?.some(tag => tag.name === 'popular' || tag.name === 'POPULAR') ||
           false;
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

  private loadApplications(): void {
    this.applicationService.getAll().subscribe({
      next: (data) => {
        this.applications.set(data);
        this.loading.set(false);
        setTimeout(() => {
          this.updateScrollButtons();
          this.updateCardOpacities();
        }, 0);
      },
      error: (err) => {
        this.error.set('Failed to load applications');
        this.loading.set(false);
        console.error('Error loading applications:', err);
      }
    });
  }

  private loadCategories(): void {
    this.applicationService.getCategoryCounts().subscribe({
      next: (data) => {
        this.categories.set(data);
      },
      error: (err) => {
        console.error('Error loading categories:', err);
      }
    });
  }

  private loadTotalDownloadsCount(): void {
    this.applicationService.getTotalDownloadsCount().subscribe({
      next: (count) => {
        this.totalDownloadsCount.set(count);
      },
      error: (err) => {
        console.error('Error loading total downloads count:', err);
      }
    });
  }

  // ==================== Logo Methods ====================

  // Track logo load errors per application
  protected logoLoadErrors = new Set<string>();

  /**
   * Check if an application has a logo
   */
  protected appHasLogo(app: Application): boolean {
    return hasLogo(app);
  }

  /**
   * Get the logo URL for an application
   */
  protected getAppLogoUrl(app: Application): string {
    return this.applicationService.getLogoUrl(app.id);
  }

  /**
   * Handle logo load error - marks the app to show fallback
   */
  protected onAppLogoError(appId: string): void {
    this.logoLoadErrors.add(appId);
  }

  /**
   * Check if should show letter avatar for an app
   */
  protected shouldShowAppLetterAvatar(app: Application): boolean {
    return !this.appHasLogo(app) || this.logoLoadErrors.has(app.id);
  }

  /**
   * Download the active containers and display a toast with the error message if necessary.
   */
  protected downloadActiveConnectors(): void {
    this.applicationService.downloadActiveConnectors().subscribe({
      next: (error) => {
        if (error) {
          this.showDownloadToast(error);
        }
      },
      error: () => this.showDownloadToast('Download of active connectors failed.')
    });
  }

  private showDownloadToast(message: string): void {
    this.toastService.show(
      'Download error',
      message,
      'danger'
    );
  }
}
