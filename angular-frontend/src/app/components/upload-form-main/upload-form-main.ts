import { Component, signal, Output, EventEmitter, Input, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Application } from '../../models/application.model';

@Component({
  selector: 'app-upload-form-main',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './upload-form-main.html',
  styleUrls: ['./upload-form-main.css']
})
export class UploadFormMain {
  @Input() readonly isOpen = signal<boolean>(false);
  @Input() readonly applications = signal<Application[]>([]);
  @Output() modalClosed = new EventEmitter<void>();

  protected readonly currentStep = signal<number>(1);
  protected readonly selectedConnectorType = signal<string>('evolveum-hosted');
  protected readonly searchQuery = signal<string>('');
  protected readonly selectedApplication = signal<Application | null>(null);

  // Step 3 - Application Details
  protected readonly displayName = signal<string>('');
  protected readonly description = signal<string>('');
  protected readonly logoFile = signal<File | null>(null);
  protected readonly origins = signal<string[]>([]);
  protected readonly loadedOrigins = signal<string[]>([]); // Track origins loaded from selected app
  protected readonly category = signal<string>('');
  protected readonly deploymentType = signal<string>('on-premise');

  protected activeApplications = computed(() => {
    return this.applications().filter(app => app.lifecycleState === 'ACTIVE');
  });

  protected filteredApplications = computed(() => {
    const query = this.searchQuery().toLowerCase().trim();
    if (!query) {
      return [];
    }
    return this.activeApplications().filter(app =>
      app.displayName.toLowerCase().includes(query) ||
      app.description.toLowerCase().includes(query)
    );
  });

  protected availableCategories = computed(() => {
    // Get unique categories from all applications
    const categoriesMap = new Map<string, string>();
    this.applications().forEach(app => {
      if (app.categories) {
        app.categories.forEach(cat => {
          categoriesMap.set(cat.name, cat.displayName);
        });
      }
    });

    return Array.from(categoriesMap.entries()).map(([name, displayName]) => ({
      name,
      displayName
    }));
  });

  protected availableCountries = computed(() => {
    // Get unique countries from all applications
    const countriesMap = new Map<string, string>();
    this.applications().forEach(app => {
      if (app.origins) {
        app.origins.forEach(origin => {
          countriesMap.set(origin.name, origin.displayName);
        });
      }
    });

    // Filter out countries that are already selected
    const selectedOrigins = this.origins();
    return Array.from(countriesMap.entries())
      .map(([name, displayName]) => displayName)
      .filter(country => !selectedOrigins.includes(country))
      .sort();
  });

  protected readonly showOriginDropdown = signal<boolean>(false);

  constructor() {}

  protected closeModal(): void {
    this.modalClosed.emit();
    this.resetForm();
  }

  protected nextStep(): void {
    if (this.currentStep() < 3) {
      // If moving from Step 2 to Step 3 and an application is selected, populate fields
      if (this.currentStep() === 2 && this.selectedApplication()) {
        this.populateApplicationDetails();
      }
      this.currentStep.update(step => step + 1);
    }
  }

  private populateApplicationDetails(): void {
    const app = this.selectedApplication();
    if (!app) return;

    // Populate basic fields
    this.displayName.set(app.displayName);
    this.description.set(app.description);
    // Note: logoFile would need special handling for URL to File conversion if needed

    // Populate origins from CountryOfOrigin objects
    if (app.origins) {
      const countries = app.origins.map(origin => origin.displayName);
      this.origins.set(countries);
      this.loadedOrigins.set(countries); // Track which origins were loaded
    }

    // Populate category from categories array (same as application-detail)
    if (app.categories && app.categories.length > 0) {
      // Taking the first category's name
      this.category.set(app.categories[0].name);
    }
  }

  protected previousStep(): void {
    if (this.currentStep() > 1) {
      this.currentStep.update(step => step - 1);
    }
  }

  protected selectConnectorType(type: string): void {
    this.selectedConnectorType.set(type);
  }

  protected onSearchChange(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.searchQuery.set(value);
  }

  protected clearSearch(): void {
    this.searchQuery.set('');
  }

  protected selectApplication(app: Application): void {
    this.selectedApplication.set(app);
    this.searchQuery.set('');
  }

  protected removeSelectedApplication(): void {
    this.selectedApplication.set(null);
    // Clear Step 3 fields when application is deselected
    this.clearApplicationDetailsFields();
  }

  private clearApplicationDetailsFields(): void {
    this.displayName.set('');
    this.description.set('');
    this.logoFile.set(null);
    this.origins.set([]);
    this.loadedOrigins.set([]);
    this.category.set('');
    this.deploymentType.set('on-premise');
    this.showOriginDropdown.set(false);
  }

  protected canContinueStep2(): boolean {
    return this.selectedApplication() !== null;
  }

  protected onLogoUpload(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.logoFile.set(input.files[0]);
    }
  }

  protected toggleOriginDropdown(): void {
    this.showOriginDropdown.update(show => !show);
  }

  protected addOriginFromDropdown(country: string): void {
    if (country && !this.origins().includes(country)) {
      this.origins.update(origins => [...origins, country]);
    }
  }

  protected removeOrigin(origin: string): void {
    this.origins.update(origins => origins.filter(o => o !== origin));
  }

  protected isOriginDismissible(origin: string): boolean {
    // Origin is dismissible if it's NOT in the list of loaded origins
    return !this.loadedOrigins().includes(origin);
  }

  protected onDescriptionChange(event: Event): void {
    const value = (event.target as HTMLTextAreaElement).value;
    if (value.length <= 350) {
      this.description.set(value);
    }
  }

  private resetForm(): void {
    this.currentStep.set(1);
    this.selectedConnectorType.set('evolveum-hosted');
    this.searchQuery.set('');
    this.selectedApplication.set(null);
    this.displayName.set('');
    this.description.set('');
    this.logoFile.set(null);
    this.origins.set([]);
    this.loadedOrigins.set([]);
    this.category.set('');
    this.deploymentType.set('on-premise');
    this.showOriginDropdown.set(false);
  }
}
