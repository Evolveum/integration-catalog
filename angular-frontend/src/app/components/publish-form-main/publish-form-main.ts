import { Component, signal, computed, effect, OnInit, OnDestroy } from '@angular/core';
import EasyMDE from 'easymde';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { NgSelectModule } from '@ng-select/ng-select';
import { Application } from '../../models/application.model';
import { ImplementationListItem } from '../../models/implementation-list-item.model';
import { CatalogConnector } from '../../models/catalog-connector.model';
import { CountryService, Country } from '../../services/country.service';
import { ApplicationService } from '../../services/application.service';
import { AuthService } from '../../services/auth.service';
import { PageHeader } from '../page-header/page-header';
import { PublishFormImpl, ReviewSummary, Step5FormData } from '../publish-form-impl/publish-form-impl';
import { CapabilityPicker, CapabilityGroup } from '../capability-picker/capability-picker';
import { OverflowTitleDirective } from '../../directives/overflow-title.directive';

@Component({
  selector: 'app-publish-form-main',
  standalone: true,
  imports: [CommonModule, FormsModule, NgSelectModule, PageHeader, PublishFormImpl, CapabilityPicker, OverflowTitleDirective],
  templateUrl: './publish-form-main.html',
  styleUrls: ['./publish-form-main.scss']
})
export class PublishFormMain implements OnInit, OnDestroy {
  private easyMde: EasyMDE | null = null;
  private modalEasyMde: EasyMDE | null = null;
  protected readonly showSideBySideModal = signal<boolean>(false);
  protected readonly sbsPreviewHtml = signal<string>('');
  protected readonly applications = signal<Application[]>([]);
  protected readonly recentlyUsedApps = signal<Application[]>([]);

  protected readonly currentStep = signal<number>(1);
  protected readonly selectedConnectorType = signal<string>('');
  protected readonly searchQuery = signal<string>('');
  protected readonly selectedApplication = signal<Application | null>(null);
  protected readonly isDefineNewMode = signal<boolean>(false);
  protected readonly showDetailsForm = signal<boolean>(false);

  // Step 2 - Application Details
  protected readonly displayName = signal<string>('');
  protected readonly description = signal<string>('');
  protected readonly logoFile = signal<File | null>(null);
  protected readonly logoPreviewUrl = signal<string | null>(null);
  protected readonly logoDragOver = signal<boolean>(false);
  protected readonly origins = signal<string[]>([]);
  protected readonly loadedOrigins = signal<string[]>([]);
  protected readonly category = signal<string>('');
  protected readonly deploymentType = signal<string>('on-premise');

  // Countries from REST API
  protected readonly allCountries = signal<Country[]>([]);
  protected readonly selectedCountriesModel = signal<Country[]>([]);
  protected readonly isLoadingCountries = signal<boolean>(true);

  // Step 3 – method-specific form fields
  protected readonly methodFormDisplayName = signal<string>('');
  protected readonly methodFormVersion     = signal<string>('1.0');
  protected readonly methodFormDescription = signal<string>('');
  protected readonly methodFormTutorial    = signal<string>('');
  protected readonly tutorialFiles         = signal<{ name: string; file: File; isNew: boolean }[]>([]);
  protected readonly tutorialDragOver      = signal<boolean>(false);
  protected readonly tutorialWordCount     = computed(() =>
    this.methodFormTutorial().trim() === '' ? 0 : this.methodFormTutorial().trim().split(/\s+/).length
  );

  // Step 3 – integration method capabilities
  protected readonly imCapabilities = signal<CapabilityGroup[]>([]);

  protected readonly selectedMethodTitles = computed(() =>
    this.integrationMethodTypes()
      .filter(m => this.selectedIntegrationMethod().includes(m.displayName))
      .map(m => m.displayName)
  );

  protected readonly selectedMethodsLabel = computed(() => {
    const titles = this.integrationMethodTypes()
      .filter(m => this.selectedIntegrationMethod().includes(m.displayName))
      .map(m => m.displayName);
    const lines: string[] = [];
    for (let i = 0; i < titles.length; i += 2) {
      lines.push(titles.slice(i, i + 2).join(' + '));
    }
    return lines.join('\n');
  });

  protected readonly showDefineNewModal = signal<boolean>(false);
  protected readonly showOriginDropdown = signal<boolean>(false);
  protected readonly isCategoryDropdownOpen = signal<boolean>(false);

  protected readonly selectedCategoryLabel = computed(() => {
    const cat = this.availableCategories().find(c => c.name === this.category());
    return cat?.displayName ?? '';
  });

  // Step 4 - Connector catalog modal
  protected readonly showConnectorCatalogModal = signal<boolean>(false);
  protected readonly catalogConnectors = signal<CatalogConnector[]>([]);
  protected readonly isCatalogLoading = signal<boolean>(false);
  protected readonly connectorCatalogSearch = signal<string>('');
  protected readonly selectedCatalogConnector = signal<CatalogConnector | null>(null);

  protected readonly filteredCatalogConnectors = computed<CatalogConnector[]>(() => {
    const query = this.connectorCatalogSearch().toLowerCase().trim();
    if (!query) return this.catalogConnectors();
    return this.catalogConnectors().filter(c =>
      c.displayName?.toLowerCase().includes(query) ||
      c.description?.toLowerCase().includes(query)
    );
  });
  protected readonly selectedIntegrationMethod = signal<string[]>([]);
  protected readonly childInternalStep = signal<number>(5);
  protected readonly childConnectorName = signal<string>('');
  protected readonly childMidpointLabel = signal<string>('');
  protected readonly effectiveStep = computed(() =>
    this.currentStep() >= 5 ? this.childInternalStep() : this.currentStep()
  );
  protected readonly connectorTypeLabel = computed(() => {
    const catalog = this.selectedCatalogConnector();
    if (catalog) return catalog.displayName;
    const labels: Record<string, string> = {
      'java-based': 'Java-based connector',
      'own-repo': 'Low-code — own repository',
      'evolveum-hosted': 'Low-code — no repository'
    };
    return labels[this.selectedConnectorType()] ?? '';
  });

  protected readonly wizardSteps = [
    { label: 'Select target application' },
    { label: 'Select integration method type' },
    { label: 'Define integration method' },
    { label: 'Select connector type' },
    { label: 'Add connector' },
    { label: 'Compatibility settings' },
    { label: 'Review' },
  ];

  protected readonly integrationMethodTypes = signal<{ id: number; displayName: string; description: string | null }[]>([]);

  protected readonly selectedMethodInfo = computed(() =>
    this.integrationMethodTypes().find(m => this.selectedIntegrationMethod().includes(m.displayName)) ?? null
  );

  protected recentApps = computed(() => this.recentlyUsedApps());

  protected filteredApplications = computed(() => {
    const query = this.searchQuery().toLowerCase().trim();
    if (!query) return [];

    if (this.currentStep() === 1) {
      return this.applications().filter(app =>
        app.displayName.toLowerCase().includes(query) ||
        app.description.toLowerCase().includes(query)
      );
    }

    const connectorType = this.selectedConnectorType();
    const targetFramework = connectorType === 'java-based' ? 'JAVA_BASED' : 'LOW_CODE';

    return this.applications().filter(app => {
      const matchesQuery = app.displayName.toLowerCase().includes(query) ||
        app.description.toLowerCase().includes(query);
      if (!matchesQuery) return false;
      if (app.lifecycleState === 'REQUESTED') return true;
      if (!app.frameworks || app.frameworks.length === 0) return false;
      return app.frameworks.includes(targetFramework);
    });
  });

  protected readonly allCategories = signal<{ name: string; displayName: string }[]>([]);

  protected availableCategories = computed(() => this.allCategories());

  protected availableCountries = computed(() => {
    const selectedOrigins = this.origins();
    return this.allCountries().filter(country => !selectedOrigins.includes(country.name));
  });

  protected deploymentOptions = computed(() => {
    const app = this.selectedApplication();

    if (!app) {
      return [
        { value: 'on-premise', label: 'On-premise' },
        { value: 'cloud-based', label: 'Cloud-based' },
        { value: 'both', label: 'Both' }
      ];
    }

    const deploymentTags = app.tags?.filter(tag => tag.tagType === 'DEPLOYMENT') || [];

    if (deploymentTags.length === 2) {
      return [{ value: 'both', label: 'Both (On-Premise and Cloud-based)' }];
    }

    if (deploymentTags.length === 0) {
      return [
        { value: 'on-premise', label: 'On-premise' },
        { value: 'cloud-based', label: 'Cloud-based' },
        { value: 'both', label: 'Both' }
      ];
    }

    const existingTag = deploymentTags[0];
    const existingValue = existingTag.name.toLowerCase().replace(/_/g, '-');
    return [
      { value: existingValue, label: existingTag.displayName },
      { value: 'both', label: 'Both (On-Premise and Cloud-based)' }
    ];
  });

  // ReviewSummary passed to the child component (steps 5–7)
  protected readonly reviewSummary = computed<ReviewSummary>(() => ({
    applicationId: this.selectedApplication()?.id ?? null,
    applicationName: this.selectedApplication()?.displayName ?? this.displayName(),
    applicationLogoUrl: (() => {
      const app = this.selectedApplication();
      return app && app.logoPath ? this.applicationService.getLogoUrl(app.id) : null;
    })(),
    applicationLogoPreviewUrl: this.logoPreviewUrl(),
    methodTitles: this.selectedMethodTitles(),
    methodTypeIds: this.integrationMethodTypes()
      .filter(m => this.selectedIntegrationMethod().includes(m.displayName))
      .map(m => m.id),
    methodName: this.methodFormDisplayName(),
    methodVersion: this.methodFormVersion(),
    methodDescription: this.methodFormDescription(),
    methodTutorial: this.methodFormTutorial(),
    applicationDescription: this.description(),
    origins: this.origins(),
    category: this.category(),
    deploymentType: this.deploymentType(),
    logoFile: this.logoFile(),
    tutorialFile: this.tutorialFiles()[0]?.file ?? null,
    imCapabilities: this.imCapabilities()
  }));

  constructor(
    private countryService: CountryService,
    private applicationService: ApplicationService,
    private authService: AuthService,
    private router: Router
  ) {
    effect(() => {
      if (this.currentStep() === 3) {
        setTimeout(() => this.initEasyMDE(), 0);
      } else {
        this.destroyEasyMDE();
      }
    });
  }

  ngOnDestroy(): void {
    this.destroyEasyMDE();
  }

  private initEasyMDE(): void {
    if (this.easyMde) return;
    const el = document.getElementById('tutorial-editor') as HTMLTextAreaElement | null;
    if (!el) return;
    this.easyMde = new EasyMDE({
      element: el,
      initialValue: this.methodFormTutorial(),
      spellChecker: false,
      status: ['lines', 'words'],
      toolbar: [
        'bold', 'italic', 'strikethrough', '|',
        'heading', 'heading-smaller', 'heading-bigger', '|',
        'quote', 'unordered-list', 'ordered-list', '|',
        'link', '|',
        'preview', '|',
        {
          name: 'side-by-side',
          action: () => this.openSideBySideModal(),
          className: 'fa fa-columns no-disable no-mobile',
          title: 'Side by Side',
        },
      ],
    });
    this.easyMde.codemirror.on('change', () => {
      this.methodFormTutorial.set(this.easyMde!.value());
    });
  }

  protected openSideBySideModal(): void {
    this.showSideBySideModal.set(true);
    document.body.style.overflow = 'hidden';
    setTimeout(() => this.initModalEasyMDE(), 0);
  }

  private initModalEasyMDE(): void {
    const el = document.getElementById('sbs-editor') as HTMLTextAreaElement | null;
    if (!el) return;
    this.modalEasyMde = new EasyMDE({
      element: el,
      initialValue: this.methodFormTutorial(),
      spellChecker: false,
      status: ['lines', 'words'],
      toolbar: [
        'bold', 'italic', 'strikethrough', '|',
        'heading', 'heading-smaller', 'heading-bigger', '|',
        'quote', 'unordered-list', 'ordered-list', '|',
        'link',
      ],
    });
    this.modalEasyMde.codemirror.on('change', () => {
      const content = this.modalEasyMde!.value();
      this.methodFormTutorial.set(content);
      this.sbsPreviewHtml.set(this.renderMarkdown(content));
    });
    this.sbsPreviewHtml.set(this.renderMarkdown(this.methodFormTutorial()));
  }

  private renderMarkdown(text: string): string {
    if (this.easyMde && typeof (this.easyMde as any).markdown === 'function') {
      return (this.easyMde as any).markdown(text);
    }
    return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/\n/g, '<br>');
  }

  protected closeSideBySideModal(): void {
    if (this.modalEasyMde) {
      const content = this.modalEasyMde.value();
      this.methodFormTutorial.set(content);
      this.easyMde?.value(content);
      this.modalEasyMde.toTextArea();
      this.modalEasyMde = null;
    }
    this.showSideBySideModal.set(false);
    document.body.style.overflow = '';
  }

  private tutorialUpload(): void {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.md,.txt';
    input.onchange = () => {
      const file = input.files?.[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = () => {
        const content = reader.result as string;
        this.easyMde!.value(content);
        this.methodFormTutorial.set(content);
      };
      reader.readAsText(file);
    };
    input.click();
  }

  private tutorialDownload(): void {
    const content = this.easyMde?.value() ?? '';
    const blob = new Blob([content], { type: 'text/markdown' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'tutorial.md';
    a.click();
    URL.revokeObjectURL(url);
  }

  private destroyEasyMDE(): void {
    if (this.modalEasyMde) {
      this.modalEasyMde.toTextArea();
      this.modalEasyMde = null;
    }
    this.showSideBySideModal.set(false);
    if (this.easyMde) {
      this.easyMde.toTextArea();
      this.easyMde = null;
    }
  }

  ngOnInit(): void {
    if (!this.authService.canUpload()) {
      this.router.navigate(['/applications']);
      return;
    }

    this.applicationService.getAll().subscribe({ next: (data) => this.applications.set(data) });

    this.applicationService.getRecentlyUsed().subscribe({ next: (data) => this.recentlyUsedApps.set(data) });

    this.applicationService.getIntegrationMethodTypes().subscribe({ next: (data) => this.integrationMethodTypes.set(data) });

    this.applicationService.getAllTags().subscribe({
      next: (tags) => this.allCategories.set(
        tags.filter(t => t.tagType === 'CATEGORY').map(t => ({ name: t.name, displayName: t.displayName }))
      )
    });

    this.countryService.getAllCountries().subscribe({
      next: (countries) => { this.allCountries.set(countries); this.isLoadingCountries.set(false); },
      error: () => { this.isLoadingCountries.set(false); this.allCountries.set([]); }
    });
  }

  protected closeModal(): void {
    this.router.navigate(['/applications']);
  }

  protected nextStep(): void {
    if (this.currentStep() < 5) {
      this.currentStep.update(step => step + 1);
    }
  }

  protected goToStep(step: number): void {
    if (step < this.currentStep()) {
      this.currentStep.set(step);
    }
  }

  protected handleGoToParentStep(step: number): void {
    this.currentStep.set(step);
    this.childInternalStep.set(5);
    this.childConnectorName.set('');
  }

  protected handleChildInternalStepChange(step: number): void {
    this.childInternalStep.set(step);
  }

  protected handleFormDataChanged(data: Step5FormData): void {
    this.childConnectorName.set(data.connectorName);
  }

  protected handleCompatibilityLabelChange(label: string): void {
    this.childMidpointLabel.set(label);
  }

  protected onMethodVersionInput(event: Event): void {
    const el = event.target as HTMLInputElement;
    const filtered = el.value.replace(/[^0-9.]/g, '');
    el.value = filtered;
    this.methodFormVersion.set(filtered);
  }

  protected onMethodVersionBlur(event: Event): void {
    const el = event.target as HTMLInputElement;
    const trimmed = el.value.replace(/\.+$/, '');
    el.value = trimmed;
    this.methodFormVersion.set(trimmed);
  }

  protected onMethodFormDescriptionChange(event: Event): void {
    const value = (event.target as HTMLTextAreaElement).value;
    if (value.length <= 350) {
      this.methodFormDescription.set(value);
    }
  }

  protected onTutorialChange(event: Event): void {
    this.methodFormTutorial.set((event.target as HTMLTextAreaElement).value);
  }

  protected onTutorialFileDrop(event: DragEvent): void {
    event.preventDefault();
    this.tutorialDragOver.set(false);
    const files = event.dataTransfer?.files;
    if (files && files.length > 0) {
      this.addTutorialFile(files[0]);
    }
  }

  protected onTutorialFileSelect(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.addTutorialFile(input.files[0]);
    }
    input.value = '';
  }

  private addTutorialFile(file: File): void {
    const allowed = ['.pdf', '.xml', '.json', '.yaml', '.yml', '.txt'];
    const ext = file.name.substring(file.name.lastIndexOf('.')).toLowerCase();
    if (!allowed.includes(ext)) {
      alert('Invalid file type. Allowed formats: PDF, XML, JSON, YAML, TXT.');
      return;
    }
    this.tutorialFiles.set([{ name: file.name, file, isNew: true }]);
  }

  protected removeTutorialFile(index: number): void {
    this.tutorialFiles.update(files => files.filter((_, i) => i !== index));
  }

  protected enterDefineNewMode(): void {
    this.clearApplicationDetailsFields();
    this.showDefineNewModal.set(true);
  }

  protected cancelDefineNewModal(): void {
    this.showDefineNewModal.set(false);
    this.clearApplicationDetailsFields();
  }

  protected confirmDefineNew(): void {
    this.showDefineNewModal.set(false);
    this.isDefineNewMode.set(true);
    this.selectedApplication.set(null);
    this.showDetailsForm.set(true);
    this.nextStep();
  }

  private populateApplicationDetails(): void {
    const app = this.selectedApplication();
    if (!app) return;

    this.displayName.set(app.displayName);
    this.description.set(app.description);

    if (app.origins) {
      const selectedCountries: Country[] = [];
      const countryNamesForBadges: string[] = [];

      app.origins.forEach(origin => {
        const countryNamePart = origin.displayName.split(',')[0].trim();

        let matchedCountry = this.allCountries().find(c =>
          c.name.toLowerCase() === countryNamePart.toLowerCase()
        );
        if (!matchedCountry) {
          matchedCountry = this.allCountries().find(c =>
            c.name.toLowerCase() === origin.name.toLowerCase()
          );
        }
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
          const customCountry: Country = { name: origin.displayName, code: origin.name.toUpperCase() };
          selectedCountries.push(customCountry);
          countryNamesForBadges.push(origin.displayName);
        }
      });

      this.origins.set(countryNamesForBadges);
      this.loadedOrigins.set(countryNamesForBadges);
      this.selectedCountriesModel.set(selectedCountries);
    }

    if (app.categories && app.categories.length > 0) {
      this.category.set(app.categories[0].name);
    }

    const deploymentTags = app.tags?.filter(tag => tag.tagType === 'DEPLOYMENT') || [];
    if (deploymentTags.length === 2) {
      this.deploymentType.set('both');
    } else if (deploymentTags.length === 1) {
      this.deploymentType.set(deploymentTags[0].name.toLowerCase().replace(/_/g, '-'));
    } else {
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
    this.selectedCatalogConnector.set(null);
  }

  protected isMethodSelected(value: string): boolean {
    return this.selectedIntegrationMethod().includes(value);
  }

  protected toggleIntegrationMethod(value: string): void {
    const current = this.selectedIntegrationMethod();
    if (current.includes(value)) {
      this.selectedIntegrationMethod.set(current.filter(v => v !== value));
    } else {
      this.selectedIntegrationMethod.set([...current, value]);
    }
  }

  protected onSearchChange(event: Event): void {
    this.searchQuery.set((event.target as HTMLInputElement).value);
  }

  protected clearSearch(): void {
    this.searchQuery.set('');
  }

  protected selectApplication(app: Application): void {
    this.selectedApplication.set(app);
    this.searchQuery.set('');
    this.isDefineNewMode.set(false);
    this.showDetailsForm.set(false);
    this.populateApplicationDetails();
    const username = this.authService.currentUser() ?? 'anonymous';
    this.applicationService.recordRecentlyUsed(app.id, username).subscribe({
      next: () => this.applicationService.getRecentlyUsed().subscribe({
        next: (data) => this.recentlyUsedApps.set(data)
      })
    });
  }

  protected continueWithSelectedApp(): void {
    this.showDetailsForm.set(true);
    this.populateApplicationDetails();
  }

  protected removeSelectedApplication(): void {
    this.selectedApplication.set(null);
    this.isDefineNewMode.set(false);
    this.showDetailsForm.set(false);
    this.clearApplicationDetailsFields();
  }

  protected clearApplicationDetailsFields(): void {
    this.displayName.set('');
    this.description.set('');
    this.clearLogo();
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

  protected canContinueStep1(): boolean {
    if (this.selectedApplication() !== null) return true;
    if (this.isDefineNewMode()) return this.canSubmitForm();
    return false;
  }

  protected canContinueWithSelection(): boolean {
    return this.selectedApplication() !== null && !this.showDetailsForm();
  }

  protected canSubmitForm(): boolean {
    return this.displayName().trim() !== '' &&
           this.logoFile() !== null;
  }

  protected onLogoUpload(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.processLogoFile(input.files[0]);
    }
  }

  protected onLogoDrop(event: DragEvent): void {
    event.preventDefault();
    this.logoDragOver.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file) this.processLogoFile(file);
  }

  private processLogoFile(file: File): void {
    const allowedTypes = ['image/png', 'image/jpeg', 'image/jpg', 'image/gif', 'image/svg+xml', 'image/webp'];
    if (!allowedTypes.includes(file.type)) {
      alert('Invalid file type. Please upload PNG, JPEG, GIF, SVG, or WebP images.');
      return;
    }
    if (file.size > 5 * 1024 * 1024) {
      alert('File too large. Maximum size is 5MB.');
      return;
    }
    this.logoFile.set(file);
    this.logoPreviewUrl.set(URL.createObjectURL(file));
  }

  protected clearLogo(): void {
    const oldUrl = this.logoPreviewUrl();
    if (oldUrl) URL.revokeObjectURL(oldUrl);
    this.logoFile.set(null);
    this.logoPreviewUrl.set(null);
  }

  protected getLogoUrl(): string | null {
    const app = this.selectedApplication();
    if (app && app.logoPath) {
      return this.applicationService.getLogoUrl(app.id);
    }
    return null;
  }

  protected getAppLogoUrl(app: Application): string | null {
    if (app.logoPath) {
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

  protected onCountriesChange(countries: Country[]): void {
    const loadedOriginNames = this.loadedOrigins();
    const currentCountryNames = countries.map(c => c.name);
    const missingLoadedOrigins = loadedOriginNames.filter(name => !currentCountryNames.includes(name));

    if (missingLoadedOrigins.length > 0) {
      const missingCountries = this.allCountries().filter(c => missingLoadedOrigins.includes(c.name));
      const restoredCountries = [...countries, ...missingCountries];
      this.selectedCountriesModel.set(restoredCountries);
      this.origins.set(restoredCountries.map(c => c.name));
    } else {
      this.selectedCountriesModel.set(countries);
      this.origins.set(countries.map(c => c.name));
    }
  }

  protected isOriginDismissible(origin: string): boolean {
    return !this.loadedOrigins().includes(origin);
  }

  protected onCategoryBlur(): void {
    setTimeout(() => this.isCategoryDropdownOpen.set(false), 150);
  }

  protected selectCategoryOption(name: string): void {
    this.category.set(name);
    this.isCategoryDropdownOpen.set(false);
  }

  protected onDescriptionChange(event: Event): void {
    const value = (event.target as HTMLTextAreaElement).value;
    if (value.length <= 350) {
      this.description.set(value);
    }
  }

  protected onImCapabilitiesChange(groups: CapabilityGroup[]): void {
    this.imCapabilities.set(groups);
  }

  protected openConnectorCatalogModal(): void {
    this.showConnectorCatalogModal.set(true);
    this.connectorCatalogSearch.set('');
    this.catalogConnectors.set([]);
    this.isCatalogLoading.set(true);
    this.applicationService.getCatalogConnectors().subscribe({
      next: (items) => { this.catalogConnectors.set(items); this.isCatalogLoading.set(false); },
      error: () => this.isCatalogLoading.set(false)
    });
  }

  protected closeConnectorCatalogModal(): void {
    this.showConnectorCatalogModal.set(false);
    this.connectorCatalogSearch.set('');
  }

  protected confirmCatalogSelection(): void {
    this.showConnectorCatalogModal.set(false);
    this.connectorCatalogSearch.set('');
    this.currentStep.set(5);
  }
}
