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
import { Observable, of } from 'rxjs';
import { switchMap } from 'rxjs/operators';
import { ApplicationService } from '../../services/application.service';
import { AuthService, UserRole } from '../../services/auth.service';
import { ImplementationListItem } from '../../models/implementation-list-item.model';
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
  tutorialFile: File | null;
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
  @Input() selectedCatalogConnector: ImplementationListItem | null = null;
  @Input() reviewSummary: ReviewSummary | null = null;

  @ViewChild(CapabilityPicker) private capabilityPicker?: CapabilityPicker;

  @Output() formValid = new EventEmitter<boolean>();
  @Output() formDataChanged = new EventEmitter<Step5FormData>();
  @Output() goToParentStep = new EventEmitter<number>();
  @Output() internalStepChange = new EventEmitter<number>();

  // Internal step: 5 = Add connector, 6 = Compatibility settings, 7 = Review
  protected readonly internalStep = signal<5 | 6 | 7>(5);

  // Accordion state
  protected readonly basicInfoExpanded = signal<boolean>(false);
  protected readonly devBuildExpanded = signal<boolean>(false);

  // Basic info
  protected readonly connectorName = signal<string>('');
  protected readonly connectorVersion = signal<string>('1.0.0');
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
  protected readonly licenseOptions = ['MIT', 'APACHE_2', 'BSD', 'EUPL'];

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

  // Compatibility step
  protected readonly midpointVersions = signal<{ id: number; version: string; versionName: string }[]>([]);
  protected readonly midpointMinVersionId = signal<number | null>(null);
  protected readonly midpointMaxVersionId = signal<number | null>(null);
  protected readonly connectorVersionFrom = signal<string>('');
  protected readonly connectorVersionTo = signal<string>('');

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

  protected readonly isGitCloneUrlInvalid = computed(() => {
    const url = this.devGitCloneUrl();
    return !!url && !url.trim().endsWith('.git');
  });

  protected readonly isCommitTagInvalid = computed(() => {
    const tag = this.devCommitTag().trim();
    return !!tag && !/^[\d.]+$/.test(tag);
  });

  protected readonly isClassNameInvalid = computed(() => {
    const name = this.devClassName().trim();
    if (!name) return false;
    return name.includes('/') || name.includes('\\') || name.includes(' ') || !name.includes('.');
  });

  protected get isStep5Valid(): boolean {
    if (!this.connectorName().trim() || !this.connectorLicense().trim()) return false;
    if (this.isExistingConnector) return true;
    if (!this.devGitCloneUrl().trim() || !this.devCommitTag().trim() || !this.devProjectFolderPath().trim()) return false;
    if (this.isGitCloneUrlInvalid()) return false;
    if (this.isCommitTagInvalid()) return false;
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
    if (!this.connectorMaintainer()) {
      this.connectorMaintainer.set(this.authService.currentUser() ?? '');
    }
    this.applicationService.getMidpointVersions().subscribe({
      next: (versions) => this.midpointVersions.set(versions)
    });
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
    this.connectorVersion.set('1.0.0');
    this.connectorMaintainer.set(this.authService.currentUser() ?? '');
    this.connectorLicense.set('');
    this.connectorDescription.set('');
    this.connectorBundleName.set('');
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
    this.publishComplete.set(false);
    this.publishCreatedOn.set(null);
    this.publishedVersionId.set(null);
    this.publishedApplicationId.set(null);
  }

  private populateFromCatalogConnector(connector: ImplementationListItem): void {
    this.connectorName.set(connector.displayName ?? '');
    this.connectorVersion.set(connector.version ?? '');
    this.connectorVersionFrom.set(connector.version ?? '');
    this.connectorMaintainer.set(connector.maintainer ?? this.authService.currentUser() ?? '');
    this.connectorLicense.set(connector.licenseType ?? '');
    this.connectorDescription.set(connector.implementationDescription ?? connector.description ?? '');
    this.connectorBundleName.set('');
    this.devProjectHomepage.set(connector.browseLink ?? '');
    this.devGitCloneUrl.set(connector.gitCloneUrl ?? '');
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
    if (this.isExistingConnector) {
      this.doPublish(this.incrementVersion(this.connectorVersion()));
      return;
    }

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

  private incrementVersion(version: string): string {
    const parts = version.split('.');
    const last = parseInt(parts[parts.length - 1], 10);
    parts[parts.length - 1] = isNaN(last) ? '1' : String(last + 1);
    return parts.join('.');
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
        id: this.isExistingConnector ? (this.selectedCatalogConnector?.id ?? null) : null,
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
        framework: this.mapConnectorTypeToFramework(this.connectorType),
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
        bundleDisplayName: this.connectorBundleName() || null
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

        const tutorialUpload$: Observable<unknown> = (integrationMethodId && summary?.tutorialFile)
          ? this.applicationService.uploadTutorial(integrationMethodId, summary.tutorialFile)
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
