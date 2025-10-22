import { Component, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApplicationService } from '../../services/application.service';
import { CategoryCount } from '../../models/category-count.model';
import { Application } from '../../models/application.model';

@Component({
  selector: 'app-request-form',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './request-form.html',
  styleUrl: './request-form.css'
})
export class RequestForm implements OnInit {
  protected loading = signal<boolean>(false);
  protected error = signal<string | null>(null);
  protected categories = signal<CategoryCount[]>([]);
  protected certificationLevels = signal<CategoryCount[]>([]);
  protected appStatuses = signal<CategoryCount[]>([]);
  protected supportedOperations = signal<CategoryCount[]>([]);
  protected isSupportedOperationsExpanded = signal<boolean>(true);
  protected isCategoryExpanded = signal<boolean>(true);
  protected isCertificationLevelExpanded = signal<boolean>(true);
  protected activeMainTab = signal<'public' | 'local'>('public');
  protected areFiltersExpanded = signal<boolean>(true);
  protected searchQuery = signal<string>('');
  protected applications = signal<Application[]>([]);
  protected pendingRequests = signal<Application[]>([]);
  protected selectedAppStatus = signal<string | null>(null);
  protected selectedSupportedOperations = signal<string[]>([]);
  protected selectedCategories = signal<string[]>([]);
  protected selectedCertificationLevels = signal<string[]>([]);
  protected viewMode = signal<'grid' | 'list'>('grid');
  protected sortBy = signal<'alphabetical' | 'popularity' | 'activity'>('alphabetical');
  protected showUnsupported = signal<boolean>(true);
  protected isRequestModalOpen = signal<boolean>(false);
  protected selectedCapabilities = signal<string[]>([]);
  protected isCapabilitiesDropdownOpen = signal<boolean>(false);
  protected formData = {
    integrationApplicationName: '',
    baseUrl: '',
    description: '',
    systemVersion: '',
    email: '',
    collab: false
  };
  protected isSubmitting = signal<boolean>(false);
  protected submitSuccess = signal<boolean>(false);
  protected submitError = signal<string | null>(null);

  protected filteredApplications = computed(() => {
    const query = this.searchQuery().toLowerCase().trim();
    const appStatus = this.selectedAppStatus();
    const supportedOps = this.selectedSupportedOperations();
    const categories = this.selectedCategories();
    const certLevels = this.selectedCertificationLevels();

    // Start with applications, and add pending requests based on filter
    let apps: Application[] = [];

    console.log('Filter - App Status:', appStatus);
    console.log('Pending requests count:', this.pendingRequests().length);

    if (!appStatus || appStatus === 'All') {
      // Show both applications and pending requests
      apps = [...this.applications(), ...this.pendingRequests()];
      console.log('Showing all - Total apps:', apps.length);
    } else if (appStatus === 'Requested By Community') {
      // Show only pending requests
      apps = [...this.pendingRequests()];
      console.log('Showing Requested By Community - Pending requests:', apps.length);
    } else if (appStatus === 'Available') {
      // Show only applications with lifecycle_state ACTIVE
      apps = this.applications().filter(app => app.lifecycleState === 'ACTIVE');
      console.log('Showing Available (ACTIVE) - Apps:', apps.length);
    } else if (appStatus === 'Pending') {
      // Show applications that are NOT ACTIVE (the rest)
      apps = this.applications().filter(app => app.lifecycleState !== 'ACTIVE');
      console.log('Showing Pending (NOT ACTIVE) - Apps:', apps.length);
    } else {
      // Filter by specific App Status (stored as tags)
      apps = this.applications().filter(app =>
        app.tags?.some(tag => tag.displayName === appStatus)
      );
      console.log('Filtered by status - Apps:', apps.length);
    }

    // Filter by Supported Operations (stored as tags)
    if (supportedOps.length > 0) {
      apps = apps.filter(app =>
        app.tags?.some(tag =>
          supportedOps.includes(tag.displayName)
        )
      );
    }

    // Filter by Categories
    if (categories.length > 0) {
      apps = apps.filter(app =>
        app.categories?.some((cat: any) =>
          categories.includes(cat.displayName)
        )
      );
    }

    // Filter by Certification Levels
    if (certLevels.length > 0) {
      apps = apps.filter(app =>
        app.tags?.some(tag =>
          certLevels.includes(tag.displayName)
        )
      );
    }

    // Filter by search query
    if (query) {
      apps = apps.filter(app =>
        app.displayName.toLowerCase().includes(query) ||
        app.description.toLowerCase().includes(query) ||
        app.lifecycleState?.toLowerCase().includes(query) ||
        app.riskLevel?.toLowerCase().includes(query) ||
        app.tags?.some(tag =>
          tag.name.toLowerCase().includes(query) ||
          tag.displayName.toLowerCase().includes(query)
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

    return apps;
  });

  protected hasActiveFilters = computed(() => {
    return this.selectedSupportedOperations().length > 0 ||
           this.selectedCategories().length > 0 ||
           this.selectedCertificationLevels().length > 0;
  });

  constructor(
    private applicationService: ApplicationService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.loadApplications();
    this.loadPendingRequests(); // This will load app statuses after pending requests are loaded
    this.loadCategories();
    this.loadCertificationLevels();
    this.loadSupportedOperations();
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

  private loadCertificationLevels(): void {
    this.applicationService.getCommonTagCounts().subscribe({
      next: (data) => {
        this.certificationLevels.set(data);
      },
      error: (err) => {
        console.error('Error loading certification levels:', err);
      }
    });
  }

  private loadAppStatuses(): void {
    this.applicationService.getAppStatusCounts().subscribe({
      next: (data) => {
        // Update the count for "All" to include applications + pending requests
        const allIndex = data.findIndex(s => s.displayName === 'All');
        if (allIndex !== -1) {
          data[allIndex].count = this.applications().length + this.pendingRequests().length;
        }

        // Update the count for "Requested By Community" to include pending requests
        const requestedByCommunityIndex = data.findIndex(s => s.displayName === 'Requested By Community');
        if (requestedByCommunityIndex !== -1) {
          data[requestedByCommunityIndex].count = this.pendingRequests().length;
        }

        // Update the count for "Available" to show applications with lifecycle_state ACTIVE
        const availableIndex = data.findIndex(s => s.displayName === 'Available');
        if (availableIndex !== -1) {
          const activeCount = this.applications().filter(app => app.lifecycleState === 'ACTIVE').length;
          data[availableIndex].count = activeCount;
        }

        // Update the count for "Pending" to show applications that are NOT ACTIVE
        const pendingIndex = data.findIndex(s => s.displayName === 'Pending');
        if (pendingIndex !== -1) {
          const notActiveCount = this.applications().filter(app => app.lifecycleState !== 'ACTIVE').length;
          data[pendingIndex].count = notActiveCount;
        }

        this.appStatuses.set(data);
      },
      error: (err) => {
        console.error('Error loading app statuses:', err);
      }
    });
  }

  private loadSupportedOperations(): void {
    this.applicationService.getSupportedOperationsCounts().subscribe({
      next: (data) => {
        this.supportedOperations.set(data);
      },
      error: (err) => {
        console.error('Error loading supported operations:', err);
      }
    });
  }

  protected goBack(): void {
    this.router.navigate(['/applications']);
  }

  protected clearFilters(): void {
    // Clear app status
    this.clearAppStatus();

    // Clear all individual filter sections
    this.clearSupportedOperations();
    this.clearCategory();
    this.clearCertificationLevel();
  }

  protected clearAppStatus(): void {
    this.selectedAppStatus.set(null);
    // Uncheck all app status radio buttons
    const radios = document.querySelectorAll<HTMLInputElement>('.app-status-radio');
    radios.forEach(radio => {
      radio.checked = false;
    });
  }

  protected clearSupportedOperations(): void {
    this.selectedSupportedOperations.set([]);
    // Uncheck all supported operations checkboxes
    const checkboxes = document.querySelectorAll<HTMLInputElement>('.supported-operations-checkbox');
    checkboxes.forEach(checkbox => {
      checkbox.checked = false;
    });
  }

  protected clearCategory(): void {
    this.selectedCategories.set([]);
    // Uncheck all category checkboxes
    const checkboxes = document.querySelectorAll<HTMLInputElement>('.category-checkbox');
    checkboxes.forEach(checkbox => {
      checkbox.checked = false;
    });
  }

  protected clearCertificationLevel(): void {
    this.selectedCertificationLevels.set([]);
    // Uncheck all certification level checkboxes
    const checkboxes = document.querySelectorAll<HTMLInputElement>('.certification-checkbox');
    checkboxes.forEach(checkbox => {
      checkbox.checked = false;
    });
  }

  protected toggleSupportedOperations(): void {
    this.isSupportedOperationsExpanded.update(value => !value);
  }

  protected toggleCategory(): void {
    this.isCategoryExpanded.update(value => !value);
  }

  protected toggleCertificationLevel(): void {
    this.isCertificationLevelExpanded.update(value => !value);
  }

  protected setActiveMainTab(tab: 'public' | 'local'): void {
    this.activeMainTab.set(tab);
  }

  protected toggleFilters(): void {
    this.areFiltersExpanded.update(value => !value);
  }

  protected onSearchChange(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.searchQuery.set(value);
  }

  protected onAppStatusChange(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    // "All" means no filter, so set to null
    this.selectedAppStatus.set(value === 'All' ? null : value);
  }

  protected onSupportedOperationChange(event: Event, operation: string): void {
    const checked = (event.target as HTMLInputElement).checked;
    if (checked) {
      this.selectedSupportedOperations.update(ops => [...ops, operation]);
    } else {
      this.selectedSupportedOperations.update(ops => ops.filter(op => op !== operation));
    }
  }

  protected onCategoryChange(event: Event, category: string): void {
    const checked = (event.target as HTMLInputElement).checked;
    if (checked) {
      this.selectedCategories.update(cats => [...cats, category]);
    } else {
      this.selectedCategories.update(cats => cats.filter(cat => cat !== category));
    }
  }

  protected onCertificationLevelChange(event: Event, certLevel: string): void {
    const checked = (event.target as HTMLInputElement).checked;
    if (checked) {
      this.selectedCertificationLevels.update(levels => [...levels, certLevel]);
    } else {
      this.selectedCertificationLevels.update(levels => levels.filter(level => level !== certLevel));
    }
  }

  protected removeAppStatusFilter(): void {
    this.clearAppStatus();
  }

  protected removeOperationsFilter(): void {
    this.clearSupportedOperations();
  }

  protected removeCategoryFilter(): void {
    this.clearCategory();
  }

  protected removeCertificationFilter(): void {
    this.clearCertificationLevel();
  }

  protected setViewMode(mode: 'grid' | 'list'): void {
    this.viewMode.set(mode);
  }

  protected onSortChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value as 'alphabetical' | 'popularity' | 'activity';
    this.sortBy.set(value);
  }

  protected toggleShowUnsupported(): void {
    this.showUnsupported.update(value => !value);
  }

  protected openRequestModal(): void {
    this.isRequestModalOpen.set(true);
  }

  protected closeRequestModal(): void {
    this.isRequestModalOpen.set(false);
    this.resetForm();
  }

  protected resetForm(): void {
    this.formData = {
      integrationApplicationName: '',
      baseUrl: '',
      description: '',
      systemVersion: '',
      email: '',
      collab: false
    };
    this.selectedCapabilities.set([]);
    this.submitSuccess.set(false);
    this.submitError.set(null);
  }

  protected submitRequest(): void {
    this.isSubmitting.set(true);
    this.submitError.set(null);

    const request = {
      integrationApplicationName: this.formData.integrationApplicationName,
      baseUrl: this.formData.baseUrl,
      capabilities: this.selectedCapabilities(),
      description: this.formData.description,
      systemVersion: this.formData.systemVersion,
      email: this.formData.email,
      collab: this.formData.collab,
      requester: this.formData.email || 'anonymous'
    };

    this.applicationService.submitPendingRequest(request).subscribe({
      next: () => {
        this.isSubmitting.set(false);
        this.closeRequestModal();
        this.submitSuccess.set(true);
        // Auto-hide success message after 5 seconds
        setTimeout(() => {
          this.submitSuccess.set(false);
        }, 5000);
      },
      error: (err) => {
        this.isSubmitting.set(false);
        this.submitError.set('Failed to submit request. Please try again.');
        console.error('Error submitting request:', err);
      }
    });
  }

  protected closeSuccessMessage(): void {
    this.submitSuccess.set(false);
  }

  protected toggleCapabilitiesDropdown(): void {
    this.isCapabilitiesDropdownOpen.update(value => !value);
  }

  protected closeCapabilitiesDropdown(): void {
    this.isCapabilitiesDropdownOpen.set(false);
  }

  protected onCapabilityChange(event: Event, capability: string): void {
    const checked = (event.target as HTMLInputElement).checked;
    if (checked) {
      this.selectedCapabilities.update(caps => [...caps, capability]);
    } else {
      this.selectedCapabilities.update(caps => caps.filter(cap => cap !== capability));
    }
  }

  protected removeCapability(capability: string, event?: Event): void {
    if (event) {
      event.stopPropagation();
    }
    this.selectedCapabilities.update(caps => caps.filter(cap => cap !== capability));
    // Uncheck the corresponding checkbox
    const checkbox = document.getElementById(`capability-${capability.toLowerCase().replace(/\s+/g, '-')}`) as HTMLInputElement;
    if (checkbox) {
      checkbox.checked = false;
    }
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
    this.loading.set(true);
    this.applicationService.getAll().subscribe({
      next: (data) => {
        this.applications.set(data);
        this.loading.set(false);
        // Update app statuses after applications are loaded
        if (this.pendingRequests().length > 0) {
          this.loadAppStatuses();
        }
      },
      error: (err) => {
        this.error.set('Failed to load applications');
        this.loading.set(false);
        console.error('Error loading applications:', err);
      }
    });
  }

  private loadPendingRequests(): void {
    this.applicationService.getPendingRequests().subscribe({
      next: (data) => {
        console.log('Loaded pending requests:', data);
        this.pendingRequests.set(data);
        // Reload app statuses after pending requests are loaded to update the count
        this.loadAppStatuses();
      },
      error: (err) => {
        console.error('Error loading pending requests:', err);
      }
    });
  }
}
