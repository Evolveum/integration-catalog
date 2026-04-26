/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, signal, computed, Output, EventEmitter, Input, OnInit, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { ApplicationService } from '../../services/application.service';
import { AuthService, UserRole } from '../../services/auth.service';
import { ImplementationListItem } from '../../models/implementation-list-item.model';

export interface ReviewSummary {
  applicationId: string | null;
  applicationName: string;
  applicationLogoUrl: string | null;
  applicationLogoPreviewUrl: string | null;
  methodTitles: string[];
  methodName: string;
  methodVersion: string;
  methodDescription: string;
  applicationDescription: string;
  origins: string[];
  category: string;
  deploymentType: string;
  logoFile: File | null;
}

export interface Step5FormData {
  connectorName: string;
  connectorVersion: string;
  connectorMaintainer: string;
  connectorLicense: string;
  connectorDescription: string;
  capabilitiesScope: 'global' | 'specific';
  globalCapabilities: string[];
  objectClassEntries: { objectClass: string; capabilities: string[] }[];
  devProjectHomepage: string;
  devSupportPortal: string;
  devBuildTool: 'maven' | 'gradle' | '';
  devGitCloneUrl: string;
  devCommitTag: string;
  devProjectFolderPath: string;
  devClassName: string;
  devRepoOwnership: 'evolveum' | 'own';
  devGithubApiKey: string;
}

@Component({
  selector: 'app-upload-form-impl',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './upload-form-impl.html',
  styleUrls: ['./upload-form-impl.scss']
})
export class UploadFormImpl implements OnInit, OnChanges {
  @Input() connectorType = '';
  @Input() selectedCatalogConnector: ImplementationListItem | null = null;
  @Input() reviewSummary: ReviewSummary | null = null;

  @Output() formValid = new EventEmitter<boolean>();
  @Output() formDataChanged = new EventEmitter<Step5FormData>();
  @Output() goToParentStep = new EventEmitter<number>();
  @Output() internalStepChange = new EventEmitter<number>();

  // Internal step: 5 = Add connector, 6 = Review
  protected readonly internalStep = signal<5 | 6>(5);

  // Accordion state
  protected readonly basicInfoExpanded = signal<boolean>(false);
  protected readonly devBuildExpanded = signal<boolean>(false);

  // Basic info
  protected readonly connectorName = signal<string>('');
  protected readonly connectorVersion = signal<string>('1.0');
  protected readonly connectorMaintainer = signal<string>('');
  protected readonly maintainerOptions = signal<string[]>([]);
  protected readonly maintainerSearch = signal<string>('');
  protected readonly isMaintainerDropdownOpen = signal<boolean>(false);
  protected readonly filteredMaintainerOptions = computed(() => {
    const search = this.maintainerSearch().toLowerCase().trim();
    const options = this.maintainerOptions();
    if (!search) return options;
    return options.filter(o => o.toLowerCase().includes(search));
  });
  protected readonly connectorLicense = signal<string>('');
  protected readonly isLicenseDropdownOpen = signal<boolean>(false);
  protected readonly connectorDescription = signal<string>('');
  protected readonly licenseOptions = ['MIT', 'APACHE_2', 'BSD', 'EUPL'];

  // Capabilities
  protected readonly capabilitiesScope = signal<'global' | 'specific'>('global');
  protected readonly globalCapabilities = signal<string[]>([]);
  protected readonly isGlobalCapDropdownOpen = signal<boolean>(false);
  protected readonly objectClassEntries = signal<{ objectClass: string; capabilities: string[]; isCapabilitiesDropdownOpen: boolean }[]>(
    [{ objectClass: '', capabilities: [], isCapabilitiesDropdownOpen: false }]
  );
  protected readonly availableCapabilities = signal<string[]>([]);
  protected readonly isLoadingCapabilities = signal<boolean>(false);

  // Dev & Build
  protected readonly devProjectHomepage = signal<string>('');
  protected readonly devSupportPortal = signal<string>('');
  protected readonly devBuildTool = signal<'maven' | 'gradle' | ''>('');
  protected readonly devGitCloneUrl = signal<string>('');
  protected readonly devCommitTag = signal<string>('');
  protected readonly devProjectFolderPath = signal<string>('');
  protected readonly devClassName = signal<string>('');
  protected readonly devRepoOwnership = signal<'evolveum' | 'own'>('evolveum');
  protected readonly devGithubApiKey = signal<string>('');
  protected readonly showGithubApiKey = signal<boolean>(false);

  // Publish state
  protected readonly isPublishing = signal<boolean>(false);
  protected readonly publishComplete = signal<boolean>(false);
  protected readonly publishCreatedOn = signal<Date | null>(null);
  protected readonly publishedVersionId = signal<string | null>(null);
  protected readonly publishedApplicationId = signal<string | null>(null);
  protected readonly emailCopied = signal<boolean>(false);
  protected readonly showVersionExistsWarning = signal<boolean>(false);
  protected readonly existingVersion = signal<string>('');

  protected get isExistingConnector(): boolean {
    return !!this.selectedCatalogConnector;
  }

  protected get isStep5Valid(): boolean {
    if (!this.connectorName().trim() || !this.connectorLicense().trim()) return false;
    if (this.isExistingConnector) return true;
    if (!this.devGitCloneUrl().trim() || !this.devCommitTag().trim() || !this.devProjectFolderPath().trim()) return false;
    if (this.connectorType === 'java-based') {
      if (!this.devBuildTool() || !this.devClassName().trim()) return false;
    }
    return true;
  }

  protected get connectorTypeLabel(): string {
    if (this.isExistingConnector) return this.selectedCatalogConnector?.displayName ?? 'Existing connector';
    const buildSuffix = this.devBuildTool() === 'maven' ? ' (Maven)' : this.devBuildTool() === 'gradle' ? ' (Gradle)' : '';
    const labels: Record<string, string> = {
      'java-based': `Java Automation${buildSuffix}`,
      'own-repo': 'Low-code — own repository',
      'evolveum-hosted': 'Low-code — Evolveum-hosted'
    };
    return labels[this.connectorType] ?? this.connectorType;
  }

  constructor(
    private applicationService: ApplicationService,
    private authService: AuthService,
    private router: Router
  ) {
    const currentUser = this.authService.currentUser();
    const role = this.authService.currentRole();
    const orgName = this.authService.currentOrganizationName();

    if (role === UserRole.Superuser) {
      this.authService.getAllMaintainers().subscribe({
        next: (all) => this.maintainerOptions.set(all),
        error: () => this.maintainerOptions.set(currentUser ? [currentUser] : [])
      });
    } else if (role === UserRole.OrganizationContributor && orgName) {
      this.maintainerOptions.set([orgName]);
    } else if (currentUser) {
      this.maintainerOptions.set([currentUser]);
    }
  }

  ngOnInit(): void {
    if (this.availableCapabilities().length === 0) this.loadCapabilities();
    if (!this.connectorMaintainer()) {
      this.connectorMaintainer.set(this.authService.currentUser() ?? '');
    }
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['connectorType'] && !changes['connectorType'].firstChange) {
      this.resetFields();
      this.internalStep.set(5);
    }
    if (changes['selectedCatalogConnector']) {
      const connector = changes['selectedCatalogConnector'].currentValue as ImplementationListItem | null;
      if (connector) {
        this.populateFromCatalogConnector(connector);
        if (this.availableCapabilities().length === 0) this.loadCapabilities();
      }
    }
    this.emitChange();
  }

  loadCapabilities(): void {
    this.isLoadingCapabilities.set(true);
    this.applicationService.getCapabilities().subscribe({
      next: (caps) => { this.availableCapabilities.set(caps); this.isLoadingCapabilities.set(false); },
      error: () => this.isLoadingCapabilities.set(false)
    });
  }

  protected nextInternalStep(): void {
    if (this.internalStep() === 5) {
      this.internalStep.set(6);
      this.internalStepChange.emit(6);
    }
  }

  protected previousInternalStep(): void {
    if (this.internalStep() === 6) {
      this.internalStep.set(5);
      this.internalStepChange.emit(5);
    } else {
      this.goToParentStep.emit(4);
    }
  }

  private resetFields(): void {
    this.connectorName.set('');
    this.connectorVersion.set('1.0');
    this.connectorMaintainer.set(this.authService.currentUser() ?? '');
    this.connectorLicense.set('');
    this.connectorDescription.set('');
    this.capabilitiesScope.set('global');
    this.globalCapabilities.set([]);
    this.objectClassEntries.set([{ objectClass: '', capabilities: [], isCapabilitiesDropdownOpen: false }]);
    this.devProjectHomepage.set('');
    this.devSupportPortal.set('');
    this.devBuildTool.set('');
    this.devGitCloneUrl.set('');
    this.devCommitTag.set('');
    this.devProjectFolderPath.set('');
    this.devClassName.set('');
    this.devRepoOwnership.set('evolveum');
    this.devGithubApiKey.set('');
    this.showGithubApiKey.set(false);
    this.publishComplete.set(false);
    this.publishCreatedOn.set(null);
    this.publishedVersionId.set(null);
    this.publishedApplicationId.set(null);
  }

  private populateFromCatalogConnector(connector: ImplementationListItem): void {
    this.connectorName.set(connector.displayName ?? '');
    this.connectorVersion.set(connector.version ?? '');
    this.connectorMaintainer.set(connector.maintainer ?? this.authService.currentUser() ?? '');
    this.connectorLicense.set(connector.licenseType ?? '');
    this.connectorDescription.set(connector.implementationDescription ?? connector.description ?? '');
    this.devProjectHomepage.set(connector.browseLink ?? '');
    this.devGitCloneUrl.set(connector.checkoutLink ?? '');
    this.devProjectFolderPath.set(connector.pathToProjectDirectory ?? '');
    this.devClassName.set(connector.className ?? '');
    const bf = (connector.buildFramework ?? '').toLowerCase();
    this.devBuildTool.set(bf === 'maven' || bf === 'gradle' ? bf as 'maven' | 'gradle' : '');
    this.devSupportPortal.set('');
    this.devCommitTag.set('');
    this.devRepoOwnership.set('evolveum');
    this.devGithubApiKey.set('');
    this.showGithubApiKey.set(false);
  }

  protected publishIntegrationMethod(): void {
    const version = this.connectorVersion() || null;
    if (version) {
      this.applicationService.checkVersionExists(version).subscribe({
        next: (exists) => {
          if (exists) {
            this.existingVersion.set(version);
            this.showVersionExistsWarning.set(true);
            setTimeout(() => this.closeVersionExistsWarning(), 5000);
          } else {
            this.doPublish();
          }
        },
        error: () => this.doPublish()
      });
    } else {
      this.doPublish();
    }
  }

  private doPublish(): void {
    const summary = this.reviewSummary;
    const payload = {
      application: {
        id: summary?.applicationId || null,
        displayName: summary?.applicationName || '',
        description: summary?.applicationDescription || '',
        logo: null,
        origins: summary?.origins || [],
        tags: [
          ...(summary?.category ? [{ name: summary.category, tagType: 'CATEGORY' as const }] : []),
          ...(summary?.deploymentType ? [{ name: summary.deploymentType, tagType: 'DEPLOYMENT' as const }] : [])
        ]
      },
      implementation: {
        implementationId: this.isExistingConnector ? (this.selectedCatalogConnector?.id ?? null) : null,
        displayName: this.connectorName(),
        description: this.connectorDescription(),
        maintainer: this.connectorMaintainer(),
        framework: this.mapConnectorTypeToFramework(this.connectorType),
        license: this.connectorLicense() || null,
        ticketingSystemLink: null,
        browseLink: this.devProjectHomepage() || null,
        checkoutLink: this.devGitCloneUrl() || null,
        buildFramework: this.devBuildTool() ? this.devBuildTool().toUpperCase() : null,
        pathToProject: this.devProjectFolderPath() || null,
        className: this.devClassName() || null,
        bundleName: null,
        connectorVersion: this.connectorVersion() || null,
        downloadLink: null,
        connidVersion: null
      },
      files: []
    };

    this.isPublishing.set(true);
    this.applicationService.uploadConnector(payload).subscribe({
      next: (response: string) => {
        this.isPublishing.set(false);
        const applicationId = summary?.applicationId || this.extractApplicationIdFromResponse(response);
        if (applicationId && summary?.logoFile) {
          this.applicationService.uploadLogo(applicationId, summary.logoFile).subscribe({ error: () => {} });
        }
        this.publishedApplicationId.set(applicationId);
        this.publishedVersionId.set(this.extractVersionIdFromResponse(response));
        this.publishCreatedOn.set(new Date());
        this.publishComplete.set(true);
      },
      error: (error: HttpErrorResponse) => {
        this.isPublishing.set(false);
        alert('Failed to publish: ' + (error.error || error.message || 'Unknown error'));
      }
    });
  }

  protected closeVersionExistsWarning(): void {
    this.showVersionExistsWarning.set(false);
    this.existingVersion.set('');
  }

  protected copyEmailToClipboard(): void {
    navigator.clipboard.writeText('help@evolveum.com').then(() => {
      this.emailCopied.set(true);
      setTimeout(() => this.emailCopied.set(false), 3000);
    });
  }

  protected navigateToAppDetail(): void {
    const id = this.publishedApplicationId() || this.reviewSummary?.applicationId;
    if (id) this.router.navigate(['/applications', id], { state: { showVersions: true } });
  }

  protected cancelPublish(): void {
    const versionId = this.publishedVersionId();
    const navigate = () => this.router.navigate(['/applications']);
    if (versionId) {
      this.applicationService.deleteImplementationVersion(versionId).subscribe({ next: navigate, error: navigate });
    } else {
      navigate();
    }
  }

  private extractApplicationIdFromResponse(response: string): string | null {
    if (response.includes('|')) return response.split('|')[0] ?? null;
    const match = response.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i);
    return match ? match[0] : null;
  }

  private extractVersionIdFromResponse(response: string): string | null {
    if (response.includes('|')) return response.split('|')[1] ?? null;
    return null;
  }

  private mapConnectorTypeToFramework(type: string): string {
    const mapping: Record<string, string> = { 'java-based': 'JAVA_BASED', 'own-repo': 'LOW_CODE', 'evolveum-hosted': 'LOW_CODE' };
    return mapping[type] || 'JAVA_BASED';
  }

  protected formatCapabilityName(cap: string): string {
    return cap.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase());
  }

  protected addObjectClassEntry(): void {
    this.objectClassEntries.update(e => [...e, { objectClass: '', capabilities: [], isCapabilitiesDropdownOpen: false }]);
  }

  protected removeObjectClassEntry(i: number): void {
    this.objectClassEntries.update(e => e.filter((_, idx) => idx !== i));
    this.emitChange();
  }

  protected updateObjectClass(i: number, value: string): void {
    this.objectClassEntries.update(e => e.map((entry, idx) => idx !== i ? entry : {
      ...entry, objectClass: value,
      isCapabilitiesDropdownOpen: value ? entry.isCapabilitiesDropdownOpen : false
    }));
    this.emitChange();
  }

  protected toggleSpecificCapDropdown(i: number): void {
    if (!this.objectClassEntries()[i]?.objectClass) return;
    this.objectClassEntries.update(e => e.map((entry, idx) =>
      idx === i ? { ...entry, isCapabilitiesDropdownOpen: !entry.isCapabilitiesDropdownOpen } : entry
    ));
  }

  protected onSpecificCapChange(event: Event, i: number, cap: string): void {
    const checked = (event.target as HTMLInputElement).checked;
    this.objectClassEntries.update(e => e.map((entry, idx) => {
      if (idx !== i) return entry;
      const capabilities = checked ? [...entry.capabilities, cap] : entry.capabilities.filter(c => c !== cap);
      return { ...entry, capabilities };
    }));
    this.emitChange();
  }

  protected removeSpecificCap(i: number, cap: string, event?: Event): void {
    event?.stopPropagation();
    this.objectClassEntries.update(e => e.map((entry, idx) =>
      idx !== i ? entry : { ...entry, capabilities: entry.capabilities.filter(c => c !== cap) }
    ));
    this.emitChange();
  }

  protected onGlobalCapChange(event: Event, cap: string): void {
    const checked = (event.target as HTMLInputElement).checked;
    this.globalCapabilities.update(caps => checked ? [...caps, cap] : caps.filter(c => c !== cap));
    this.emitChange();
  }

  protected removeGlobalCap(cap: string, event?: Event): void {
    event?.stopPropagation();
    this.globalCapabilities.update(caps => caps.filter(c => c !== cap));
    this.emitChange();
  }

  protected onMaintainerInput(event: Event): void {
    this.maintainerSearch.set((event.target as HTMLInputElement).value);
    this.isMaintainerDropdownOpen.set(true);
  }

  protected onMaintainerFocus(): void {
    this.maintainerSearch.set('');
    this.isMaintainerDropdownOpen.set(true);
  }

  protected onMaintainerBlur(): void {
    setTimeout(() => this.isMaintainerDropdownOpen.set(false), 150);
  }

  protected selectMaintainerOption(option: string): void {
    this.connectorMaintainer.set(option);
    this.maintainerSearch.set('');
    this.isMaintainerDropdownOpen.set(false);
    this.emitChange();
  }

  protected onLicenseBlur(): void {
    setTimeout(() => this.isLicenseDropdownOpen.set(false), 150);
  }

  protected selectLicenseOption(opt: string): void {
    this.connectorLicense.set(opt);
    this.isLicenseDropdownOpen.set(false);
    this.emitChange();
  }

  protected onFieldChange(): void {
    this.emitChange();
  }

  private emitChange(): void {
    this.formValid.emit(this.isStep5Valid);
    this.formDataChanged.emit({
      connectorName: this.connectorName(),
      connectorVersion: this.connectorVersion(),
      connectorMaintainer: this.connectorMaintainer(),
      connectorLicense: this.connectorLicense(),
      connectorDescription: this.connectorDescription(),
      capabilitiesScope: this.capabilitiesScope(),
      globalCapabilities: this.globalCapabilities(),
      objectClassEntries: this.objectClassEntries().map(e => ({ objectClass: e.objectClass, capabilities: e.capabilities })),
      devProjectHomepage: this.devProjectHomepage(),
      devSupportPortal: this.devSupportPortal(),
      devBuildTool: this.devBuildTool(),
      devGitCloneUrl: this.devGitCloneUrl(),
      devCommitTag: this.devCommitTag(),
      devProjectFolderPath: this.devProjectFolderPath(),
      devClassName: this.devClassName(),
      devRepoOwnership: this.devRepoOwnership(),
      devGithubApiKey: this.devGithubApiKey()
    });
  }
}
