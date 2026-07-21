/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, signal, computed, Output, EventEmitter, Input, OnInit, OnChanges, SimpleChanges, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, of, forkJoin } from 'rxjs';
import { MidpointVersion } from '../../models/application-detail.model';
import { switchMap } from 'rxjs/operators';
import { ApplicationService } from '../../services/application.service';
import { AuthService, UserRole } from '../../services/auth.service';
import { ImplementationListItem } from '../../models/implementation-list-item.model';
import { CatalogConnector } from '../../models/catalog-connector.model';
import { IntegrationMethodCapabilityGroup } from '../../models/request.model';
import { CapabilityPicker, CapabilityGroup } from '../capability-picker/capability-picker';

export interface ReviewSummary {
  applicationId: string | null;
  applicationName: string;
  applicationLogoUrl: string | null;
  applicationLogoPreviewUrl: string | null;
  methodTitles: string[];
  methodTypeIds: number[];
  methodName: string;
  methodVersion: string;
  methodDescription: string;
  methodTutorial: string;
  applicationDescription: string;
  origins: string[];
  category: string;
  deploymentType: string;
  logoFile: File | null;
  tutorialFiles: File[];
  imCapabilities: IntegrationMethodCapabilityGroup[];
}

export interface Step5FormData {
  connectorName: string;
  connectorVersion: string;
  connectorMaintainer: string;
  connectorLicense: string;
  connectorDescription: string;
  connectorBundleName: string;
  connectorCapabilityGroups: CapabilityGroup[];
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
  selector: 'app-publish-form-impl',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, CapabilityPicker],
  templateUrl: './publish-form-impl.html',
  styleUrls: ['./publish-form-impl.scss']
})
export class PublishFormImpl implements OnInit, OnChanges {
  @Input() connectorType = '';
  @Input() selectedCatalogConnector: CatalogConnector | null = null;
  @Input() reviewSummary: ReviewSummary | null = null;
  @Input() currentParentStep: number = 0;

  @ViewChild(CapabilityPicker) private capabilityPicker?: CapabilityPicker;

  @Output() formValid = new EventEmitter<boolean>();
  @Output() formDataChanged = new EventEmitter<Step5FormData>();
  @Output() goToParentStep = new EventEmitter<number>();
  @Output() internalStepChange = new EventEmitter<number>();
  @Output() compatibilityLabelChange = new EventEmitter<string>();

  // Internal step: 5 = Add connector, 6 = Compatibility settings, 7 = Review
  protected readonly internalStep = signal<5 | 6 | 7>(5);

  // Accordion state
  protected readonly basicInfoExpanded = signal<boolean>(true);
  protected readonly devBuildExpanded = signal<boolean>(true);

  // Basic info
  protected readonly connectorName = signal<string>('');
  // New connectors are always versioned 1.0.0 (field is read-only); existing catalog connectors overwrite this.
  protected readonly connectorVersion = signal<string>('');
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
  protected readonly connectorBundleName = signal<string>('');
  protected readonly bundleNameTaken = signal<boolean>(false);
  protected readonly licenseOptions = ['MIT', 'APACHE_2', 'BSD', 'EUPL'];
  protected readonly licenseLabels: Record<string, string> = {
    'MIT': 'MIT',
    'APACHE_2': 'Apache 2.0',
    'BSD': 'BSD',
    'EUPL': 'EUPL 1.2'
  };
  protected fmtLicense(key: string): string {
    return this.licenseLabels[key] ?? key;
  }

  // Connector capabilities (from CapabilityPicker child)
  protected readonly connectorCapabilities = signal<CapabilityGroup[]>([]);

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
  protected readonly devSourceFile = signal<File | null>(null);
  protected readonly devSourceFileDragOver = signal<boolean>(false);

  // Compatibility step
  protected readonly midpointVersions = signal<MidpointVersion[]>([]);
  protected readonly midpointMinVersionId = signal<number | null>(null);
  protected readonly midpointMaxVersionId = signal<number | null>(null);
  protected readonly compatInfoDismissed = signal<boolean>(false);
  protected readonly connectorVersionFrom = signal<string>('');
  protected readonly connectorVersionTo = signal<string>('');

  // Publish state
  protected readonly publishConfirmed = signal<boolean>(false);
  protected readonly licenseExpanded = signal<boolean>(false);
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

  protected get isJavaBasedConnector(): boolean {
    if (this.isExistingConnector) {
      return this.selectedCatalogConnector?.bundleFramework === 'JAVA_BASED';
    }
    return this.connectorType === 'java-based';
  }

  protected get isCompatibilityStepValid(): boolean {
    return this.midpointMinVersionId() !== null && !this.isMidpointVersionRangeInvalid();
  }

  protected getMidpointVersionLabel(id: number | null): string {
    if (id === null) return '';
    return this.midpointVersions().find(v => v.id === id)?.version ?? '';
  }

  protected readonly isMidpointVersionRangeInvalid = computed(() => {
    const minId = this.midpointMinVersionId();
    const maxId = this.midpointMaxVersionId();
    if (!minId || !maxId) return false;
    const versions = this.midpointVersions();
    const minIndex = versions.findIndex(v => v.id === minId);
    const maxIndex = versions.findIndex(v => v.id === maxId);
    return maxIndex < minIndex;
  });

  protected readonly midpointCompatLabel = computed(() => {
    const minLabel = this.getMidpointVersionLabel(this.midpointMinVersionId());
    if (!minLabel) return '';
    const maxLabel = this.getMidpointVersionLabel(this.midpointMaxVersionId());
    return maxLabel ? `${minLabel} – ${maxLabel}` : `${minLabel}+`;
  });

  protected readonly isConnectorVersionInvalid = computed(() => {
    if (this.isExistingConnector) return false;
    const v = this.connectorVersion().trim();
    if (!v) return false;
    return (v.match(/\./g) ?? []).length < 2;
  });

  protected readonly isGitCloneUrlInvalid = computed(() => {
    const url = this.devGitCloneUrl();
    return !!url && !url.trim().endsWith('.git');
  });

  protected readonly isClassNameInvalid = computed(() => {
    const name = this.devClassName().trim();
    if (!name) return false;
    return name.includes('/') || name.includes('\\') || name.includes(' ') || !name.includes('.');
  });

  protected get isStep5Valid(): boolean {
    if (!this.connectorName().trim() || !this.connectorLicense().trim()) return false;
    if (this.isExistingConnector) return true;
    if (this.connectorCapabilities().length === 0) return false;
    if (!this.connectorVersion().trim()) return false;
    if (this.isConnectorVersionInvalid()) return false;
    if (this.bundleNameTaken()) return false;
    if (this.connectorType !== 'evolveum-hosted') {
      if (!this.devGitCloneUrl().trim() || !this.devCommitTag().trim()) return false;
      if (this.isGitCloneUrlInvalid()) return false;
    }
    if (this.connectorType === 'java-based') {
      if (!this.devBuildTool() || !this.devClassName().trim()) return false;
    }
    if (this.isClassNameInvalid()) return false;
    return true;
  }

  protected get connectorTypeLabel(): string {
    if (this.isExistingConnector) return this.selectedCatalogConnector?.displayName ?? 'Existing connector';
    const buildSuffix = this.devBuildTool() === 'maven' ? ' (Maven)' : this.devBuildTool() === 'gradle' ? ' (Gradle)' : '';
    const labels: Record<string, string> = {
      'java-based': 'Java-based connector',
      'own-repo': 'Low-code — own repository',
      'evolveum-hosted': 'Low-code — no repository'
    };
    return labels[this.connectorType] ?? this.connectorType;
  }

  constructor(
    private applicationService: ApplicationService,
    private authService: AuthService,
    private router: Router
  ) {
    if (this.authService.currentRole() === UserRole.Superuser) {
      const currentUser = this.authService.currentUser();
      this.authService.getAllMaintainers().subscribe({
        next: (all) => this.maintainerOptions.set(all),
        error: () => this.maintainerOptions.set(currentUser ? [currentUser] : [])
      });
    } else {
      this.maintainerOptions.set(this.authService.maintainerOptions());
    }
  }

  ngOnInit(): void {
    if (!this.connectorMaintainer()) {
      this.connectorMaintainer.set(this.authService.defaultMaintainer());
    }
    this.applicationService.getMidpointVersions().subscribe({
      next: (versions) => this.midpointVersions.set(versions)
    });
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['currentParentStep']) {
      const prev = changes['currentParentStep'].previousValue as number;
      const curr = changes['currentParentStep'].currentValue as number;
      if (prev >= 5 && curr < 5) {
        this.internalStep.set(5);
        this.internalStepChange.emit(5);
      }
    }
    if (changes['connectorType'] && !changes['connectorType'].firstChange) {
      this.resetFields();
      this.internalStep.set(5);
    }
    if (changes['selectedCatalogConnector']) {
      const connector = changes['selectedCatalogConnector'].currentValue as CatalogConnector | null;
      if (connector) {
        this.populateFromCatalogConnector(connector);
      } else if (!changes['selectedCatalogConnector'].firstChange) {
        this.resetFields();
      }
    }
    this.emitChange();
  }

  protected onConnectorCapabilitiesChange(groups: CapabilityGroup[]): void {
    this.connectorCapabilities.set(groups);
    this.emitChange();
  }

  protected nextInternalStep(): void {
    if (this.internalStep() === 5) {
      this.internalStep.set(6);
      this.internalStepChange.emit(6);
    } else if (this.internalStep() === 6) {
      this.compatibilityLabelChange.emit(this.midpointCompatLabel());
      this.internalStep.set(7);
      this.internalStepChange.emit(7);
    }
  }

  protected previousInternalStep(): void {
    if (this.internalStep() === 7) {
      this.internalStep.set(6);
      this.internalStepChange.emit(6);
    } else if (this.internalStep() === 6) {
      this.internalStep.set(5);
      this.internalStepChange.emit(5);
    } else {
      this.goToParentStep.emit(4);
    }
  }

  private resetFields(): void {
    this.connectorName.set('');
    this.connectorVersion.set('');
    this.connectorVersionFrom.set('');
    this.connectorVersionTo.set('');
    this.connectorMaintainer.set(this.authService.defaultMaintainer());
    this.connectorLicense.set('');
    this.connectorDescription.set('');
    this.connectorBundleName.set('');
    this.bundleNameTaken.set(false);
    this.connectorCapabilities.set([]);
    this.capabilityPicker?.reset();
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
    this.devSourceFile.set(null);
    this.devSourceFileDragOver.set(false);
    this.publishComplete.set(false);
    this.publishCreatedOn.set(null);
    this.publishedVersionId.set(null);
    this.publishedApplicationId.set(null);
  }

  private populateFromCatalogConnector(connector: CatalogConnector): void {
    this.connectorName.set(connector.displayName ?? '');
    this.connectorVersion.set(connector.version ?? '');
    this.connectorVersionFrom.set(connector.version ?? '');
    this.connectorMaintainer.set(connector.maintainer ?? this.authService.defaultMaintainer());
    this.connectorLicense.set(connector.licenseType ?? '');
    this.connectorDescription.set(connector.description ?? '');
    this.connectorBundleName.set(connector.bundleDisplayName ?? '');
    this.devProjectHomepage.set(connector.browseLink ?? '');
    this.devGitCloneUrl.set(connector.gitCloneUrl ?? '');
    this.devProjectFolderPath.set(connector.pathToProject ?? '');
    this.devClassName.set(connector.className ?? '');
    const bf = (connector.buildFramework ?? '').toLowerCase();
    this.devBuildTool.set(bf === 'maven' || bf === 'gradle' ? bf as 'maven' | 'gradle' : '');
    this.devSupportPortal.set('');
    this.devCommitTag.set('');
    this.devRepoOwnership.set('evolveum');
    this.devGithubApiKey.set('');
    this.showGithubApiKey.set(false);

    const caps: CapabilityGroup[] = (connector.objectClassCapabilities ?? []).map(oc => ({
      objectClass: oc.objectName,
      capabilityNames: oc.capabilities ?? []
    }));
    this.connectorCapabilities.set(caps);
  }

  protected publishIntegrationMethod(): void {
    this.doPublish();
  }

  private doPublish(versionOverride?: string): void {
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
      integrationMethod: {
        id: null,
        displayName: summary?.methodName ?? '',
        revision: summary?.methodVersion ?? '',
        description: summary?.methodDescription ?? '',
        tutorial: summary?.methodTutorial ?? '',
        typeIds: summary?.methodTypeIds ?? [],
        midpointMinVersion: this.midpointMinVersionId(),
        midpointMaxVersion: this.midpointMaxVersionId()
      },
      connector: {
        displayName: this.connectorName(),
        description: this.connectorDescription(),
        maintainer: this.connectorMaintainer(),
        framework: this.isExistingConnector
            ? (this.selectedCatalogConnector?.bundleFramework ?? 'JAVA_BASED')
            : this.mapConnectorTypeToFramework(this.connectorType),
        license: this.connectorLicense() || null,
        ticketingSystemLink: this.devSupportPortal() || null,
        browseLink: this.devProjectHomepage() || null,
        gitCloneUrl: this.devGitCloneUrl() || null,
        buildFramework: this.devBuildTool() ? this.devBuildTool().toUpperCase() : null,
        pathToProject: this.devProjectFolderPath() || null,
        className: this.devClassName() || null,
        bundleName: null,
        version: versionOverride ?? this.connectorVersion() ?? null,
        commitTag: this.devCommitTag() || null,
        bundleDisplayName: this.connectorBundleName() || null,
        connectorBundleId: this.isExistingConnector ? (this.selectedCatalogConnector?.id ?? null) : null
      },
      files: [],
      integrationMethodCapabilities: summary?.imCapabilities ?? [],
      connectorCapabilities: this.connectorCapabilities()
    };

    this.isPublishing.set(true);
    this.applicationService.uploadConnector(payload).pipe(
      switchMap((response: string) => {
        const applicationId = summary?.applicationId || this.extractApplicationIdFromResponse(response);
        const integrationMethodId = this.extractVersionIdFromResponse(response);
        this.publishedApplicationId.set(applicationId);
        this.publishedVersionId.set(integrationMethodId);

        const logoUpload$: Observable<unknown> = (applicationId && summary?.logoFile)
          ? this.applicationService.uploadLogo(applicationId, summary.logoFile)
          : of(null);

        const tutorialFiles = summary?.tutorialFiles ?? [];
        const tutorialUpload$: Observable<unknown> = (integrationMethodId && tutorialFiles.length > 0)
          ? forkJoin(tutorialFiles.map(file => this.applicationService.uploadTutorial(integrationMethodId, file)))
          : of(null);

        return logoUpload$.pipe(
          switchMap(() => tutorialUpload$),
          switchMap(() => of(response))
        );
      })
    ).subscribe({
      next: () => {
        this.isPublishing.set(false);
        this.publishCreatedOn.set(new Date());
        this.publishComplete.set(true);
      },
      error: (error: HttpErrorResponse) => {
        this.isPublishing.set(false);
        const isPostPublishError = !!this.publishedApplicationId();
        if (isPostPublishError) {
          this.publishCreatedOn.set(new Date());
          this.publishComplete.set(true);
          console.error('Logo or tutorial upload failed:', error);
        } else {
          alert('Failed to publish: ' + (error.error || error.message || 'Unknown error'));
        }
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

  protected printConsentDocument(): void {
    const consentParagraphs = [
      `By publishing this integration method, you confirm that you hold all rights necessary to publish this content ` +
        `under the license you have selected, that the content does not infringe any third-party rights, and that it ` +
        `complies with Evolveum's Terms of Use and Acceptable Use Policy.`,
      `You grant Evolveum a perpetual, irrevocable, non-exclusive, royalty-free, worldwide license to reproduce, adapt, ` +
        `modify, translate, publish, publicly perform, publicly display and distribute this content solely for the ` +
        `purpose of hosting and displaying it in the Integration Catalog under the license you have selected. Any ` +
        `tutorial, documentation, or descriptive text accompanying your submission will be published under Evolveum's ` +
        `standard documentation license, the Creative Commons Attribution-NonCommercial-NoDerivatives 4.0 International ` +
        `(CC BY-NC-ND 4.0) license, regardless of the license you have selected for the connector or configuration ` +
        `itself. Evolveum reserves the right to remove or decline publication of any submitted content at its sole ` +
        `discretion, including but not limited to content that violates these Terms of Use or the Acceptable Use Policy.`,
    ];

    const printWindow = window.open('', '_blank', 'width=800,height=600');
    if (!printWindow) return;

    printWindow.document.write(`
      <html>
        <head>
          <title>Publishing consent document</title>
          <style>
            body { font-family: Arial, Helvetica, sans-serif; color: #1a202c; line-height: 1.6; margin: 2.5rem; }
            h1 { font-size: 1.25rem; margin-bottom: 1.5rem; }
            p { font-size: 0.9rem; margin: 0 0 1rem; }
          </style>
        </head>
        <body>
          <h1>Publishing consent document</h1>
          ${consentParagraphs.map((p) => `<p>${p}</p>`).join('')}
        </body>
      </html>
    `);
    printWindow.document.close();
    printWindow.focus();
    printWindow.print();
  }

  protected cancelPublish(): void {
    this.router.navigate(['/applications']);
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

  protected onSourceFileSelect(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0] ?? null;
    if (file) { this.devSourceFile.set(file); this.onFieldChange(); }
  }

  protected onSourceFileDrop(event: DragEvent): void {
    event.preventDefault();
    this.devSourceFileDragOver.set(false);
    const file = event.dataTransfer?.files?.[0] ?? null;
    if (file) { this.devSourceFile.set(file); this.onFieldChange(); }
  }

  protected onBundleNameBlur(): void {
    const name = this.connectorBundleName().trim();
    if (!name) return;
    this.applicationService.checkBundleNameExists(name).subscribe({
      next: (exists) => this.bundleNameTaken.set(exists),
      error: () => this.bundleNameTaken.set(false)
    });
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
      connectorBundleName: this.connectorBundleName(),
      connectorCapabilityGroups: this.connectorCapabilities(),
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
