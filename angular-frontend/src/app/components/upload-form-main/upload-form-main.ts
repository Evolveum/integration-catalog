import { Component, signal, Output, EventEmitter, Input, computed, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NgSelectModule } from '@ng-select/ng-select';
import { Application } from '../../models/application.model';
import { CountryService, Country } from '../../services/country.service';
import { ApplicationService } from '../../services/application.service';
import { UploadFormImpl } from '../upload-form-impl/upload-form-impl';

@Component({
  selector: 'app-upload-form-main',
  standalone: true,
  imports: [CommonModule, FormsModule, NgSelectModule, UploadFormImpl],
  templateUrl: './upload-form-main.html',
  styleUrls: ['./upload-form-main.css']
})
export class UploadFormMain implements OnInit {
  @Input() isOpen = signal<boolean>(false);
  @Input() applications = signal<Application[]>([]);
  @Output() modalClosed = new EventEmitter<void>();
  @Output() uploadCompleted = new EventEmitter<void>();

  @ViewChild(UploadFormImpl) uploadFormImpl!: UploadFormImpl;

  protected readonly currentStep = signal<number>(1);
  protected readonly selectedConnectorType = signal<string>('');
  protected readonly searchQuery = signal<string>('');
  protected readonly selectedApplication = signal<Application | null>(null);
  protected readonly isDefineNewMode = signal<boolean>(false);
  protected readonly showDetailsForm = signal<boolean>(false);

  // Step 2 - Application Details (shown in Define Target App step when app is selected or Define New is clicked)
  protected readonly displayName = signal<string>('');
  protected readonly description = signal<string>('');
  protected readonly logoFile = signal<File | null>(null);
  protected readonly logoPreviewUrl = signal<string | null>(null);
  protected readonly origins = signal<string[]>([]);
  protected readonly loadedOrigins = signal<string[]>([]); // Track origins loaded from selected app
  protected readonly category = signal<string>('');
  protected readonly deploymentType = signal<string>('on-premise');

  // Countries from REST API
  protected readonly allCountries = signal<Country[]>([]);
  protected readonly selectedCountriesModel = signal<Country[]>([]);
  protected readonly isLoadingCountries = signal<boolean>(true);

  // Step 3 - Implementation form data
  protected readonly implementationFormData = signal<any>(null);
  protected readonly isImplementationFormValid = signal<boolean>(false);

  // Toast notifications
  protected readonly showPublishSuccess = signal<boolean>(false);
  protected readonly showVersionExistsWarning = signal<boolean>(false);
  protected readonly existingVersion = signal<string>('');

  protected filteredApplications = computed(() => {
    const query = this.searchQuery().toLowerCase().trim();
    if (!query) {
      return [];
    }

    const connectorType = this.selectedConnectorType();
    const targetFramework = connectorType === 'java-based' ? 'CONNID' : 'SCIM_REST';

    // Debug: log all apps and their frameworks
    console.log('All applications:', this.applications().map(a => ({
      name: a.displayName,
      lifecycleState: a.lifecycleState,
      frameworks: a.frameworks
    })));
    console.log('Target framework:', targetFramework);

    return this.applications().filter(app => {
      // First filter by search query
      const matchesQuery = app.displayName.toLowerCase().includes(query) ||
        app.description.toLowerCase().includes(query);

      if (!matchesQuery) {
        return false;
      }

      // Always show REQUESTED apps (they have no implementations yet)
      if (app.lifecycleState === 'REQUESTED') {
        return true;
      }

      // For other apps, check if they have the matching framework
      // If no frameworks (empty or null), don't show the app
      if (!app.frameworks || app.frameworks.length === 0) {
        return false;
      }

      return app.frameworks.includes(targetFramework);
    });
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
    // Filter out countries that are already selected
    const selectedOrigins = this.origins();
    return this.allCountries()
      .filter(country => !selectedOrigins.includes(country.name));
  });

  protected deploymentOptions = computed(() => {
    const app = this.selectedApplication();

    // If no app selected (Define New), show all 3 options
    if (!app) {
      return [
        { value: 'on-premise', label: 'On-premise' },
        { value: 'cloud-based', label: 'Cloud-based' },
        { value: 'both', label: 'Both' }
      ];
    }

    // Get deployment tags from selected app
    const deploymentTags = app.tags?.filter(tag => tag.tagType === 'DEPLOYMENT') || [];

    // If has both deployment types, show only "Both" option
    if (deploymentTags.length === 2) {
      return [
        { value: 'both', label: 'Both (On-Premise and Cloud-based)' }
      ];
    }

    // If has no deployment type, show all 3 options
    if (deploymentTags.length === 0) {
      return [
        { value: 'on-premise', label: 'On-premise' },
        { value: 'cloud-based', label: 'Cloud-based' },
        { value: 'both', label: 'Both' }
      ];
    }

    // If has one deployment type, show existing one + "Both" option
    const existingTag = deploymentTags[0];
    const existingValue = existingTag.name.toLowerCase().replace(/_/g, '-');

    return [
      { value: existingValue, label: existingTag.displayName },
      { value: 'both', label: 'Both (On-Premise and Cloud-based)' }
    ];
  });

  protected readonly showOriginDropdown = signal<boolean>(false);

  constructor(
    private countryService: CountryService,
    private applicationService: ApplicationService
  ) {}

  ngOnInit(): void {
    // Fetch all countries from REST Countries API
    this.countryService.getAllCountries().subscribe({
      next: (countries) => {
        this.allCountries.set(countries);
        this.isLoadingCountries.set(false);
      },
      error: (error) => {
        console.error('Failed to fetch countries from REST API:', error);
        this.isLoadingCountries.set(false);
        // Fallback to empty array if API fails
        this.allCountries.set([]);
      }
    });
  }

  protected closeModal(): void {
    this.modalClosed.emit();
    this.resetForm();
  }

  protected closePublishSuccessToast(): void {
    this.showPublishSuccess.set(false);
  }

  protected nextStep(): void {
    if (this.currentStep() < 3) {
      this.currentStep.update(step => step + 1);
    }
  }

  protected enterDefineNewMode(): void {
    this.isDefineNewMode.set(true);
    this.selectedApplication.set(null);
    this.showDetailsForm.set(true);
    this.clearApplicationDetailsFields();
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
      const selectedCountries: Country[] = [];
      const countryNamesForBadges: string[] = [];

      app.origins.forEach(origin => {
        // Extract country name from displayName (e.g., "Austria, Europe" -> "Austria")
        const countryNamePart = origin.displayName.split(',')[0].trim();

        // Try to match by the extracted country name (case-insensitive)
        let matchedCountry = this.allCountries().find(c =>
          c.name.toLowerCase() === countryNamePart.toLowerCase()
        );

        // If not found, try matching against the lowercase 'name' field from DB
        if (!matchedCountry) {
          matchedCountry = this.allCountries().find(c =>
            c.name.toLowerCase() === origin.name.toLowerCase()
          );
        }

        // If still not found, try partial match
        if (!matchedCountry) {
          matchedCountry = this.allCountries().find(c =>
            c.name.toLowerCase().includes(countryNamePart.toLowerCase()) ||
            countryNamePart.toLowerCase().includes(c.name.toLowerCase())
          );
        }

        if (matchedCountry) {
          selectedCountries.push(matchedCountry);
          countryNamesForBadges.push(matchedCountry.name);
        } else {
          // Create a custom Country object for non-matched entries (like states, custom locations, etc.)
          const customCountry: Country = {
            name: origin.displayName,
            code: origin.name.toUpperCase()
          };
          selectedCountries.push(customCountry);
          countryNamesForBadges.push(origin.displayName);
        }
      });

      this.origins.set(countryNamesForBadges);
      this.loadedOrigins.set(countryNamesForBadges); // Track which origins were loaded
      this.selectedCountriesModel.set(selectedCountries);
    }

    // Populate category from categories array (same as application-detail)
    if (app.categories && app.categories.length > 0) {
      // Taking the first category's name
      this.category.set(app.categories[0].name);
    }

    // Populate deployment type from deployment tags
    const deploymentTags = app.tags?.filter(tag => tag.tagType === 'DEPLOYMENT') || [];
    if (deploymentTags.length === 2) {
      // Has both deployment types
      this.deploymentType.set('both');
    } else if (deploymentTags.length === 1) {
      // Has one deployment type - use it as default
      const tagValue = deploymentTags[0].name.toLowerCase().replace(/_/g, '-');
      this.deploymentType.set(tagValue);
    } else {
      // No deployment type - keep default
      this.deploymentType.set('on-premise');
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
    this.isDefineNewMode.set(false);
    this.showDetailsForm.set(false);
  }

  protected continueWithSelectedApp(): void {
    this.showDetailsForm.set(true);
    // Populate fields when continuing with selected app
    this.populateApplicationDetails();
  }

  protected removeSelectedApplication(): void {
    this.selectedApplication.set(null);
    this.isDefineNewMode.set(false);
    this.showDetailsForm.set(false);
    // Clear fields when application is deselected
    this.clearApplicationDetailsFields();
  }

  protected clearApplicationDetailsFields(): void {
    this.displayName.set('');
    this.description.set('');
    this.clearLogo(); // Clear logo file and preview
    this.origins.set([]);
    this.loadedOrigins.set([]);
    this.selectedCountriesModel.set([]);
    this.category.set('');
    this.deploymentType.set('on-premise');
    this.showOriginDropdown.set(false);
  }

  protected showImplementationForm(): boolean {
    return this.showDetailsForm();
  }

  protected canContinueWithSelection(): boolean {
    return this.selectedApplication() !== null && !this.showDetailsForm();
  }

  protected canSubmitForm(): boolean {
    return this.displayName().trim() !== '' &&
           this.description().trim() !== '' &&
           this.category() !== '' &&
           this.deploymentType() !== '';
  }

  protected onLogoUpload(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      const file = input.files[0];

      // Validate file type
      const allowedTypes = ['image/png', 'image/jpeg', 'image/jpg', 'image/gif', 'image/svg+xml', 'image/webp'];
      if (!allowedTypes.includes(file.type)) {
        alert('Invalid file type. Please upload PNG, JPEG, GIF, SVG, or WebP images.');
        return;
      }

      // Validate file size (5MB max)
      const maxSize = 5 * 1024 * 1024;
      if (file.size > maxSize) {
        alert('File too large. Maximum size is 5MB.');
        return;
      }

      this.logoFile.set(file);

      // Create preview URL
      const previewUrl = URL.createObjectURL(file);
      this.logoPreviewUrl.set(previewUrl);
    }
  }

  protected clearLogo(): void {
    // Revoke the old preview URL to prevent memory leaks
    const oldUrl = this.logoPreviewUrl();
    if (oldUrl) {
      URL.revokeObjectURL(oldUrl);
    }
    this.logoFile.set(null);
    this.logoPreviewUrl.set(null);
  }

  protected getLogoUrl(): string | null {
    const app = this.selectedApplication();
    if (app && (app.logoPath || app.logo)) {
      return this.applicationService.getLogoUrl(app.id);
    }
    return null;
  }

  protected toggleOriginDropdown(): void {
    this.showOriginDropdown.update(show => !show);
  }

  protected addOriginFromDropdown(country: Country): void {
    if (country && !this.origins().includes(country.name)) {
      this.origins.update(origins => [...origins, country.name]);
    }
  }

  protected removeOrigin(origin: string): void {
    this.origins.update(origins => origins.filter(o => o !== origin));
  }

  // Handle changes from ng-select
  protected onCountriesChange(countries: Country[]): void {
    // Prevent removal of origins loaded from DB
    const loadedOriginNames = this.loadedOrigins();
    const currentCountryNames = countries.map(c => c.name);

    // Check if any loaded origins were removed
    const missingLoadedOrigins = loadedOriginNames.filter(name => !currentCountryNames.includes(name));

    if (missingLoadedOrigins.length > 0) {
      // Re-add the missing loaded origins
      const allCountries = this.allCountries();
      const missingCountries = allCountries.filter(c => missingLoadedOrigins.includes(c.name));
      const restoredCountries = [...countries, ...missingCountries];
      this.selectedCountriesModel.set(restoredCountries);
      this.origins.set(restoredCountries.map(c => c.name));
    } else {
      this.selectedCountriesModel.set(countries);
      this.origins.set(countries.map(c => c.name));
    }
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

  // Handle implementation form events
  protected onImplementationFormDataChange(data: any): void {
    this.implementationFormData.set(data);
  }

  protected onImplementationFormValidChange(isValid: boolean): void {
    this.isImplementationFormValid.set(isValid);
  }

  protected handleImplementationAction(): void {
    console.log('handleImplementationAction called');
    const formData = this.implementationFormData();
    console.log('formData:', formData);
    console.log('isNewVersion:', formData?.isNewVersion);
    console.log('isEditingVersion:', formData?.isEditingVersion);
    console.log('selectedImplementation:', formData?.selectedImplementation);

    // If selecting an existing implementation (isNewVersion = true and not editing yet)
    if (formData?.isNewVersion === true && formData?.selectedImplementation && !formData?.isEditingVersion && this.uploadFormImpl) {
      console.log('Calling confirmImplementationSelection');
      this.uploadFormImpl.confirmImplementationSelection();
    } else {
      // Handle publish action for new implementation or editing existing
      console.log('Calling publishToCatalog');
      this.publishToCatalog();
    }
  }

  private publishToCatalog(): void {
    console.log('publishToCatalog called');
    const formData = this.implementationFormData();
    console.log('Form data:', formData);

    if (!formData) {
      alert('Form data is missing. Please fill out all required fields.');
      return;
    }

    // Extract version from browseLink URL (e.g., https://github.com/user/repo/tree/v1.3 -> v1.3)
    const connectorVersionFromUrl = this.extractVersionFromBrowseLink(formData.browseLink);

    // Parse uploaded JSON file to get files array and extract version/bundleName if available
    const parsedFile = this.parseUploadedJsonFile(formData.uploadedFile);

    // Use version from URL first, then from uploaded file
    const connectorVersion = connectorVersionFromUrl || parsedFile.connectorVersion || null;
    const bundleName = parsedFile.bundleName || null;

    console.log('Final connectorVersion:', connectorVersion);
    console.log('Final bundleName:', bundleName);

    // Check if version already exists before publishing
    if (connectorVersion) {
      this.applicationService.checkVersionExists(connectorVersion).subscribe({
        next: (exists: boolean) => {
          if (exists) {
            // Show warning and don't proceed
            this.existingVersion.set(connectorVersion);
            this.showVersionExistsWarning.set(true);
          } else {
            // Version doesn't exist, proceed with upload
            this.doPublish(formData, connectorVersion, bundleName, parsedFile.files);
          }
        },
        error: (error: any) => {
          console.error('Version check failed:', error);
          // On error, proceed with upload anyway (let backend handle it)
          this.doPublish(formData, connectorVersion, bundleName, parsedFile.files);
        }
      });
    } else {
      // No version specified, proceed with upload
      this.doPublish(formData, connectorVersion, bundleName, parsedFile.files);
    }
  }

  protected closeVersionExistsWarning(): void {
    this.showVersionExistsWarning.set(false);
    this.existingVersion.set('');
  }

  private doPublish(formData: any, connectorVersion: string | null, bundleName: string | null, files: any[]): void {
    // Build the payload - must match backend UploadImplementationDto structure
    const payload = {
      application: {
        id: this.selectedApplication()?.id || null,
        displayName: this.displayName(),
        description: this.description(),
        logo: null,
        // Send origins as simple array of country names
        origins: this.origins(),
        // Send tags as simple array of tag names with types
        tags: [
          ...(this.category() ? [{ name: this.category(), tagType: 'CATEGORY' }] : []),
          ...(this.deploymentType() ? [{ name: this.deploymentType(), tagType: 'DEPLOYMENT' }] : [])
        ]
      },
      implementation: {
        implementationId: formData.selectedImplementation?.id || null, // Include implementation ID if adding new version
        displayName: formData.displayName,
        description: formData.implementationDescription,
        maintainer: formData.maintainer,
        framework: this.mapConnectorTypeToFramework(this.selectedConnectorType()),
        license: formData.licenseType || null, // Send null instead of empty string for enum
        ticketingSystemLink: formData.ticketingLink || null,
        browseLink: formData.browseLink || null,
        checkoutLink: formData.checkoutLink || null,
        buildFramework: formData.buildFramework ? formData.buildFramework.toUpperCase() : null,
        pathToProject: formData.pathToProjectDirectory || null,
        className: formData.className || null,
        bundleName: bundleName,
        connectorVersion: connectorVersion,
        downloadLink: null,
        connidVersion: null
      },
      files: files
    };

    console.log('Publishing payload:', payload);
    // console.log('Publishing payload (JSON):', JSON.stringify(payload, null, 2));

    this.applicationService.uploadConnector(payload).subscribe({
      next: (response: string) => {
        console.log('Upload successful:', response);

        // Upload logo if present - try to extract application ID from response
        // Response might be application ID or a message containing it
        const applicationId = this.selectedApplication()?.id || this.extractApplicationIdFromResponse(response);
        if (applicationId) {
          this.uploadLogoIfPresent(applicationId);
        }

        this.closeModal();
        this.showPublishSuccess.set(true);
        this.uploadCompleted.emit();
        setTimeout(() => this.showPublishSuccess.set(false), 5000);
      },
      error: (error: any) => {
        console.error('Upload failed:', error);
        alert('Failed to publish: ' + (error.error || error.message || 'Unknown error'));
      }
    });
  }

  private extractApplicationIdFromResponse(response: string): string | null {
    // Try to extract UUID from response string
    const uuidMatch = response.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i);
    return uuidMatch ? uuidMatch[0] : null;
  }

  private mapConnectorTypeToFramework(connectorType: string): string {
    const mapping: Record<string, string> = {
      'java-based': 'CONNID',
      'own-repo': 'SCIM_REST',
      'evolveum-hosted': 'SCIM_REST'
    };
    return mapping[connectorType] || 'CONNID';
  }

  private extractVersionFromBrowseLink(browseLink: string | null): string | null {
    if (!browseLink) {
      return null;
    }
    // Match URLs ending with /tree/{version}
    const treeMatch = browseLink.match(/\/tree\/([^\/]+)$/);
    if (treeMatch) {
      let version = treeMatch[1];
      // Remove 'v' prefix if present (e.g., "v1.3" -> "1.3")
      if (version.toLowerCase().startsWith('v')) {
        version = version.substring(1);
      }
      return version;
    }
    return null;
  }

  private parseUploadedJsonFile(uploadedFile: {name: string, data: string} | null): {files: any[], connectorVersion?: string, bundleName?: string} {
    if (!uploadedFile || !uploadedFile.data) {
      return {
        files: []
      };
    }

    // Only parse JSON files
    if (!uploadedFile.name.toLowerCase().endsWith('.json')) {
      console.log('Uploaded file is not a JSON file, skipping parse:', uploadedFile.name);
      // Backend expects ItemFile with 'path' and 'content' fields
      return {
        files: [{
          path: uploadedFile.name,
          content: uploadedFile.data
        }]
      };
    }

    try {
      // Decode base64 data to string
      const jsonString = atob(uploadedFile.data);
      console.log('=== DEBUG: Parsing JSON file ===');
      console.log('File name:', uploadedFile.name);
      console.log('Decoded JSON string (first 500 chars):', jsonString.substring(0, 500));
      const parsedJson = JSON.parse(jsonString);
      console.log('Parsed JSON:', parsedJson);

      // If the JSON has a "files" wrapper, extract files and version info
      if (parsedJson.files && Array.isArray(parsedJson.files)) {
        console.log('Extracted files array from wrapper');

        // Try to extract version from connector.manifest.json or MANIFEST.MF
        let connectorVersion: string | undefined;
        let bundleName: string | undefined;

        for (const file of parsedJson.files) {
          if (file.path === 'connector.manifest.json' && file.content) {
            try {
              const manifest = JSON.parse(file.content);
              if (manifest.connector?.version) {
                connectorVersion = manifest.connector.version;
                console.log('Extracted connectorVersion from connector.manifest.json:', connectorVersion);
              }
              if (manifest.connector?.artifactId) {
                bundleName = manifest.connector.artifactId;
                console.log('Extracted bundleName from connector.manifest.json:', bundleName);
              }
            } catch (e) {
              console.log('Could not parse connector.manifest.json content');
            }
          }

          // Also check MANIFEST.MF as fallback
          if (file.path === 'META-INF/MANIFEST.MF' && file.content && !connectorVersion) {
            const versionMatch = file.content.match(/ConnectorBundle-Version:\s*(.+)/);
            if (versionMatch) {
              connectorVersion = versionMatch[1].trim();
              console.log('Extracted connectorVersion from MANIFEST.MF:', connectorVersion);
            }
            const nameMatch = file.content.match(/ConnectorBundle-Name:\s*(.+)/);
            if (nameMatch && !bundleName) {
              bundleName = nameMatch[1].trim();
              console.log('Extracted bundleName from MANIFEST.MF:', bundleName);
            }
          }
        }

        return {
          files: parsedJson.files,
          ...(connectorVersion && { connectorVersion }),
          ...(bundleName && { bundleName })
        };
      }

      return parsedJson;
    } catch (e) {
      console.error('Failed to parse uploaded JSON file:', e);
      console.error('Sending raw file as base64 instead');
      // If parsing fails, send the raw file data
      // Backend expects ItemFile with 'path' and 'content' fields
      return {
        files: [{
          path: uploadedFile.name,
          content: uploadedFile.data
        }]
      };
    }
  }

  protected handleBackFromImplementation(): void {
    const formData = this.implementationFormData();

    // If in editing mode, go back to implementation selection
    if (formData?.isEditingVersion && this.uploadFormImpl) {
      this.uploadFormImpl.cancelEditing();
    } else {
      // Otherwise, go back to previous step
      this.previousStep();
    }
  }

  private resetForm(): void {
    this.currentStep.set(1);
    this.selectedConnectorType.set('evolveum-hosted');
    this.searchQuery.set('');
    this.selectedApplication.set(null);
    this.isDefineNewMode.set(false);
    this.showDetailsForm.set(false);
    this.displayName.set('');
    this.description.set('');
    this.clearLogo(); // Clear logo file and preview
    this.origins.set([]);
    this.loadedOrigins.set([]);
    this.selectedCountriesModel.set([]);
    this.category.set('');
    this.deploymentType.set('on-premise');
    this.showOriginDropdown.set(false);
  }

  /**
   * Upload logo for an application after it's created/updated
   */
  private uploadLogoIfPresent(applicationId: string): void {
    const logoFile = this.logoFile();
    if (logoFile && applicationId) {
      this.applicationService.uploadLogo(applicationId, logoFile).subscribe({
        next: () => {
          console.log('Logo uploaded successfully for application:', applicationId);
        },
        error: (error: any) => {
          console.error('Failed to upload logo:', error);
          // Don't block the main flow, logo upload is secondary
        }
      });
    }
  }
}
