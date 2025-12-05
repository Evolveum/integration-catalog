/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, OnInit, signal, computed, ViewChild, ViewChildren, ElementRef, AfterViewInit, QueryList } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApplicationService } from '../../services/application.service';
import { Application } from '../../models/application.model';
import { CategoryCount } from '../../models/category-count.model';
import { RequestForm } from '../request-form/request-form';
import { LoginModal } from '../login-modal/login-modal';
import { UploadFormMain } from '../upload-form-main/upload-form-main';
import { FilterModal, FilterState } from '../filter-modal/filter-modal';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-applications-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RequestForm, LoginModal, UploadFormMain, FilterModal],
  templateUrl: './applications-list.html',
  styleUrls: ['./applications-list.css']
})
export class ApplicationsList implements OnInit, AfterViewInit {
  @ViewChild('scrollContainer') scrollContainer!: ElementRef<HTMLDivElement>;
  @ViewChild('scrollContainerMore') scrollContainerMore!: ElementRef<HTMLDivElement>;
  @ViewChildren('featuredCard') featuredCards!: QueryList<ElementRef<HTMLDivElement>>;

  protected applications = signal<Application[]>([]);
  protected readonly categories = signal<CategoryCount[]>([]);
  protected readonly totalDownloadsCount = signal<number>(0);

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

  protected readonly allAppStatuses = [
    'ACTIVE',
    'REQUESTED',
    'WITH_ERROR',
    'IN_PUBLISH_PROCESS'
  ];
  protected readonly loading = signal<boolean>(true);
  protected readonly error = signal<string | null>(null);
  protected readonly searchQuery = signal('');
  protected readonly canScrollLeft = signal<boolean>(false);
  protected readonly canScrollRight = signal<boolean>(false);
  protected readonly currentPage = signal<number>(0);
  protected readonly itemsPerPage = 12;
  protected readonly sortBy = signal<'alphabetical' | 'popularity' | 'activity'>('alphabetical');
  protected readonly viewMode = signal<'grid' | 'list'>('grid');
  protected readonly activeTab = signal<string>('all');
  protected isRequestModalOpen = signal<boolean>(false);
  protected isLoginModalOpen = signal<boolean>(false);
  protected isUploadModalOpen = signal<boolean>(false);
  protected isFilterModalOpen = signal<boolean>(false);
  protected openDropdown = signal<string | null>(null);
  protected showLoginRequiredMessage = signal<boolean>(false);
  protected dropdownPosition = signal<{ top: number; left: number } | null>(null);

  protected filterState = signal<FilterState>({
    trending: false,
    categories: [],
    capabilities: [],
    appStatus: [],
    midpointVersions: []
  });

  protected readonly currentUser = computed(() => this.authService.currentUser());

  protected readonly featuredApplications = computed(() => {
    const query = this.searchQuery().toLowerCase().trim();
    const activeTab = this.activeTab();
    const filters = this.filterState();
    const apps = this.applications();

    // Don't show featured apps when searching or filtering
    const hasActiveFilters = filters.trending ||
                            filters.categories.length > 0 ||
                            filters.capabilities.length > 0 ||
                            filters.appStatus.length > 0 ||
                            filters.midpointVersions.length > 0;

    if (query || activeTab !== 'all' || hasActiveFilters) {
      return [];
    }
    return apps;
  });

  protected readonly moreApplications = computed(() => {
    const query = this.searchQuery().toLowerCase().trim();
    const activeTab = this.activeTab();
    const filters = this.filterState();
    let apps = [...this.applications()];

    // Filter by category tab (if not 'all')
    if (activeTab !== 'all') {
      apps = apps.filter(app =>
        app.categories?.some((category: any) => category.displayName === activeTab)
      );
    }

    // When searching, show all matching apps. When not searching, show all apps
    if (query) {
      apps = apps.filter(app =>
        app.displayName.toLowerCase().includes(query)
      );
    }

    // Apply advanced filters
    if (filters.trending) {
      apps = apps.filter(app => app.tags?.some(tag => tag.name === 'popular'));
    }

    if (filters.categories.length > 0) {
      apps = apps.filter(app => {
        const allTags = [...(app.categories || []), ...(app.tags || [])];
        return allTags.some(tag =>
          filters.categories.includes(tag.name) &&
          (tag.tagType === 'CATEGORY' || tag.tagType === 'DEPLOYMENT')
        );
      });
    }

    if (filters.capabilities.length > 0) {
      apps = apps.filter(app =>
        app.capabilities?.some((capability: string) =>
          filters.capabilities.includes(capability)
        )
      );
    }

    if (filters.appStatus.length > 0) {
      apps = apps.filter(app =>
        app.lifecycleState && filters.appStatus.includes(app.lifecycleState)
      );
    }

    if (filters.midpointVersions.length > 0) {
      apps = apps.filter(app =>
        app.midpointVersions?.some((version: string) =>
          filters.midpointVersions.includes(version)
        )
      );
    }

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
    let apps = this.applications();

    // Filter by category tab (if not 'all')
    if (activeTab !== 'all') {
      apps = apps.filter(app =>
        app.categories?.some((category: any) => category.displayName === activeTab)
      );
    }

    if (query) {
      apps = apps.filter(app =>
        app.displayName.toLowerCase().includes(query)
      );
    }

    // Apply advanced filters
    if (filters.trending) {
      apps = apps.filter(app => app.tags?.some(tag => tag.name === 'popular'));
    }

    if (filters.categories.length > 0) {
      apps = apps.filter(app => {
        const allTags = [...(app.categories || []), ...(app.tags || [])];
        return allTags.some(tag =>
          filters.categories.includes(tag.name) &&
          (tag.tagType === 'CATEGORY' || tag.tagType === 'DEPLOYMENT')
        );
      });
    }

    if (filters.capabilities.length > 0) {
      apps = apps.filter(app =>
        app.capabilities?.some((capability: string) =>
          filters.capabilities.includes(capability)
        )
      );
    }

    if (filters.appStatus.length > 0) {
      apps = apps.filter(app =>
        app.lifecycleState && filters.appStatus.includes(app.lifecycleState)
      );
    }

    if (filters.midpointVersions.length > 0) {
      apps = apps.filter(app =>
        app.midpointVersions?.some((version: string) =>
          filters.midpointVersions.includes(version)
        )
      );
    }

    return apps.length;
  });

  protected readonly totalPages = computed(() => {
    return Math.ceil(this.filteredCount() / this.itemsPerPage);
  });

  constructor(
    private applicationService: ApplicationService,
    private router: Router,
    protected authService: AuthService
  ) {}

  ngOnInit(): void {
    this.loadApplications();
    this.loadCategories();
    this.loadTotalDownloadsCount();
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
      capabilities: [],
      appStatus: [],
      midpointVersions: []
    });
    this.currentPage.set(0);
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

  protected removeCategoryFilter(category: string): void {
    this.filterState.update(state => ({
      ...state,
      categories: state.categories.filter(c => c !== category)
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

  protected removeMidpointVersionFilter(version: string): void {
    this.filterState.update(state => ({
      ...state,
      midpointVersions: state.midpointVersions.filter(v => v !== version)
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

  protected toggleMidpointVersionInFilter(version: string): void {
    const versions = this.filterState().midpointVersions;
    if (versions.includes(version)) {
      this.removeMidpointVersionFilter(version);
    } else {
      this.filterState.update(state => ({
        ...state,
        midpointVersions: [...state.midpointVersions, version]
      }));
      this.currentPage.set(0);
    }
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
    const categoriesMap = new Map<string, string>();

    for (const app of this.applications()) {
      const allTags = [...(app.categories || []), ...(app.tags || [])];
      for (const tag of allTags) {
        if (tag.tagType === 'CATEGORY' || tag.tagType === 'DEPLOYMENT') {
          categoriesMap.set(tag.name, tag.displayName);
        }
      }
    }

    return Array.from(categoriesMap.entries())
      .map(([name, displayName]) => ({ name, displayName }))
      .sort((a, b) => a.displayName.localeCompare(b.displayName));
  }

  protected isCategorySelected(category: string): boolean {
    return this.filterState().categories.includes(category);
  }

  protected isCapabilitySelected(capability: string): boolean {
    return this.filterState().capabilities.includes(capability);
  }

  protected isAppStatusSelected(status: string): boolean {
    return this.filterState().appStatus.includes(status);
  }

  protected isMidpointVersionSelected(version: string): boolean {
    return this.filterState().midpointVersions.includes(version);
  }

  protected allMidpointVersions = computed(() => {
    const versionsSet = new Set<string>();
    for (const app of this.applications()) {
      if (app.midpointVersions) {
        app.midpointVersions.forEach(v => versionsSet.add(v));
      }
    }
    return Array.from(versionsSet).sort();
  });

  protected onSortChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value as 'alphabetical' | 'popularity' | 'activity';
    this.sortBy.set(value);
    this.currentPage.set(0);
  }

  protected setViewMode(mode: 'grid' | 'list'): void {
    this.viewMode.set(mode);
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
    if (!this.authService.isLoggedIn()) {
      this.showLoginRequiredMessage.set(true);
      setTimeout(() => this.showLoginRequiredMessage.set(false), 5000);
      this.isLoginModalOpen.set(true);
      return;
    }
    this.isRequestModalOpen.set(true);
  }

  protected closeLoginRequiredMessage(): void {
    this.showLoginRequiredMessage.set(false);
  }

  protected closeRequestModal(): void {
    this.isRequestModalOpen.set(false);
  }

  protected openLoginModal(): void {
    this.isLoginModalOpen.set(true);
  }

  protected closeLoginModal(): void {
    this.isLoginModalOpen.set(false);
  }

  protected openUploadModal(): void {
    if (!this.authService.isLoggedIn()) {
      this.showLoginRequiredMessage.set(true);
      setTimeout(() => this.showLoginRequiredMessage.set(false), 5000);
      this.isLoginModalOpen.set(true);
      return;
    }
    this.isUploadModalOpen.set(true);
  }

  protected closeUploadModal(): void {
    this.isUploadModalOpen.set(false);
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
  }

  protected logout(): void {
    this.authService.logout();
  }

  protected voteForRequest(app: Application): void {
    const currentUser = this.currentUser();

    if (!currentUser) {
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
      case 'IN_PUBLISH_PROCESS':
        return 'Publishing...';
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
}
