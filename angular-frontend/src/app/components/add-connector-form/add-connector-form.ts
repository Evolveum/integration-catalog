/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import {
  Component, Input, Output, EventEmitter, OnInit,
  signal, computed
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApplicationService } from '../../services/application.service';
import { AuthService, UserRole } from '../../services/auth.service';
import { CapabilityPicker, CapabilityGroup } from '../capability-picker/capability-picker';
import { PageHeader } from '../page-header/page-header';
import { CatalogConnector } from '../../models/catalog-connector.model';

type Step = 1 | 2;

@Component({
  selector: 'app-add-connector-form',
  standalone: true,
  imports: [CommonModule, FormsModule, CapabilityPicker, PageHeader],
  templateUrl: './add-connector-form.html',
  styleUrls: ['./add-connector-form.scss']
})
export class AddConnectorForm implements OnInit {
  @Input() appId = '';
  @Input() versionId = '';
  @Input() revision = '';
  @Input() appName = '';
  @Input() appHasLogo = false;
  @Input() logoUrl = '';
  @Output() close = new EventEmitter<void>();
  @Output() saved = new EventEmitter<void>();

  protected readonly step = signal<Step>(1);
  protected readonly isSaving = signal<boolean>(false);
  protected readonly saveError = signal<string>('');
  protected readonly logoLoadError = signal<boolean>(false);

  // ── Step 1: Connector type ────────────────────────────────
  protected readonly connectorType = signal<string>('java-based');

  // Catalog modal
  protected readonly showCatalogModal = signal<boolean>(false);
  protected readonly catalogConnectors = signal<CatalogConnector[]>([]);
  protected readonly isCatalogLoading = signal<boolean>(false);
  protected readonly catalogSearch = signal<string>('');
  protected readonly pendingCatalogConnector = signal<CatalogConnector | null>(null);
  protected readonly selectedCatalogConnector = signal<CatalogConnector | null>(null);

  protected readonly filteredCatalogConnectors = computed(() => {
    const q = this.catalogSearch().toLowerCase().trim();
    return this.catalogConnectors().filter(c =>
      !q ||
      c.displayName.toLowerCase().includes(q) ||
      (c.bundleDisplayName ?? '').toLowerCase().includes(q)
    );
  });

  // ── Step 2: Connector details ─────────────────────────────
  protected readonly basicInfoExpanded = signal<boolean>(true);
  protected readonly devBuildExpanded = signal<boolean>(true);

  protected readonly connectorName = signal<string>('');
  // New connectors are always versioned 1.0.0 (field is read-only); existing catalog connectors overwrite this.
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
  protected readonly bundleNameTaken = signal<boolean>(false);
  protected readonly connectorCapabilities = signal<CapabilityGroup[]>([]);
  protected readonly initialCapabilities = signal<CapabilityGroup[]>([]);

  protected readonly devProjectHomepage = signal<string>('');
  protected readonly devSupportPortal = signal<string>('');
  protected readonly devBuildTool = signal<'maven' | 'gradle' | ''>('');
  protected readonly devGitCloneUrl = signal<string>('');
  protected readonly devCommitTag = signal<string>('');
  protected readonly devProjectFolderPath = signal<string>('');
  protected readonly devClassName = signal<string>('');

  protected readonly licenseOptions = ['MIT', 'APACHE_2', 'BSD', 'EUPL'];
  protected readonly licenseLabels: Record<string, string> = {
    'MIT': 'MIT', 'APACHE_2': 'Apache 2.0', 'BSD': 'BSD', 'EUPL': 'EUPL 1.2'
  };

  // ── Computed helpers ──────────────────────────────────────
  protected get isExistingConnector(): boolean {
    return !!this.selectedCatalogConnector();
  }

  protected get isJavaBasedConnector(): boolean {
    if (this.isExistingConnector) {
      return this.selectedCatalogConnector()?.bundleFramework === 'JAVA_BASED';
    }
    return this.connectorType() === 'java-based';
  }

  protected readonly isConnectorVersionInvalid = computed(() => {
    if (this.selectedCatalogConnector()) return false;
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

  protected readonly isStep2Valid = computed(() => {
    if (this.selectedCatalogConnector()) return true;
    const base = !!this.connectorName().trim()
      && !!this.connectorVersion().trim()
      && !!this.connectorMaintainer().trim()
      && !!this.connectorLicense();
    if (!base || this.isConnectorVersionInvalid() || this.bundleNameTaken()) return false;
    const devOk = !!this.devGitCloneUrl().trim()
      && !!this.devCommitTag().trim()
      && !this.isGitCloneUrlInvalid();
    const javaOk = !this.isJavaBasedConnector
      || (!!this.devBuildTool() && !!this.devClassName().trim() && !this.isClassNameInvalid());
    return devOk && javaOk;
  });

  constructor(
    private appService: ApplicationService,
    private authService: AuthService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.connectorMaintainer.set(this.authService.currentUser() ?? '');
    this.initMaintainerOptions();
  }

  private initMaintainerOptions(): void {
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
  }

  protected fmtLicense(key: string): string {
    return this.licenseLabels[key] ?? key;
  }

  protected showLogo(): boolean {
    return this.appHasLogo && !this.logoLoadError();
  }

  protected onLogoError(): void {
    this.logoLoadError.set(true);
  }

  // ── Step 1 actions ────────────────────────────────────────
  protected selectConnectorType(type: string): void {
    const wasExisting = this.selectedCatalogConnector() !== null;
    const typeChanged = this.connectorType() !== type;
    this.connectorType.set(type);
    this.selectedCatalogConnector.set(null);
    // Switching connector option must start from a clean custom form (no stale prefill).
    if (wasExisting || typeChanged) {
      this.resetConnectorFields();
    }
  }

  protected openCatalogModal(): void {
    this.pendingCatalogConnector.set(this.selectedCatalogConnector());
    this.catalogSearch.set('');
    this.showCatalogModal.set(true);
    if (this.catalogConnectors().length === 0) {
      this.isCatalogLoading.set(true);
      this.appService.getCatalogConnectors().subscribe({
        next: c => { this.catalogConnectors.set(c); this.isCatalogLoading.set(false); },
        error: () => this.isCatalogLoading.set(false)
      });
    }
  }

  protected closeCatalogModal(): void {
    this.showCatalogModal.set(false);
  }

  protected confirmCatalogSelection(): void {
    const c = this.pendingCatalogConnector();
    if (!c) return;
    this.selectedCatalogConnector.set(c);
    // Clear any prior selection's prefill before applying the newly chosen connector.
    this.resetConnectorFields();
    this.populateFromCatalogConnector(c);
    this.showCatalogModal.set(false);
    this.step.set(2);
  }

  /** Reset every connector-detail field to its default (used when the selection changes). */
  private resetConnectorFields(): void {
    this.connectorName.set('');
    this.connectorVersion.set('1.0.0');
    this.connectorMaintainer.set(this.authService.currentUser() ?? '');
    this.maintainerSearch.set('');
    this.isMaintainerDropdownOpen.set(false);
    this.connectorLicense.set('');
    this.isLicenseDropdownOpen.set(false);
    this.connectorDescription.set('');
    this.connectorBundleName.set('');
    this.bundleNameTaken.set(false);
    this.connectorCapabilities.set([]);
    this.initialCapabilities.set([]);
    this.devProjectHomepage.set('');
    this.devSupportPortal.set('');
    this.devBuildTool.set('');
    this.devGitCloneUrl.set('');
    this.devCommitTag.set('');
    this.devProjectFolderPath.set('');
    this.devClassName.set('');
  }

  private populateFromCatalogConnector(c: CatalogConnector): void {
    this.connectorName.set(c.displayName ?? '');
    this.connectorVersion.set(c.version ?? '');
    this.connectorMaintainer.set(c.maintainer ?? this.authService.currentUser() ?? '');
    this.connectorLicense.set(c.licenseType ?? '');
    this.devProjectHomepage.set(c.browseLink ?? '');
    this.devGitCloneUrl.set(c.gitCloneUrl ?? '');
    this.devProjectFolderPath.set(c.pathToProject ?? '');
    this.devClassName.set(c.className ?? '');
    const bf = (c.buildFramework ?? '').toLowerCase();
    this.devBuildTool.set(bf === 'maven' || bf === 'gradle' ? bf as 'maven' | 'gradle' : '');
  }

  // ── Step 2 actions ────────────────────────────────────────
  protected onBundleNameBlur(): void {
    const name = this.connectorBundleName().trim();
    if (!name) return;
    this.appService.checkBundleNameExists(name).subscribe({
      next: exists => this.bundleNameTaken.set(exists),
      error: () => this.bundleNameTaken.set(false)
    });
  }

  protected onLicenseBlur(): void {
    setTimeout(() => this.isLicenseDropdownOpen.set(false), 150);
  }

  protected selectLicenseOption(opt: string): void {
    this.connectorLicense.set(opt);
    this.isLicenseDropdownOpen.set(false);
  }

  protected onCapabilitiesChange(caps: CapabilityGroup[]): void {
    this.connectorCapabilities.set(caps);
  }

  // ── Navigation ────────────────────────────────────────────
  protected goToCatalog(): void {
    this.router.navigate(['/applications']);
  }

  protected goToApp(): void {
    this.router.navigate(['/applications', this.appId]);
  }

  protected back(): void {
    const s = this.step();
    if (s > 1) this.step.set((s - 1) as Step);
    else this.close.emit();
  }

  // ── Save ──────────────────────────────────────────────────
  protected save(): void {
    this.isSaving.set(true);
    this.saveError.set('');
    const cc = this.selectedCatalogConnector();
    const payload = {
      existingConnectorId: cc ? cc.id : null,
      displayName: this.connectorName(),
      description: this.connectorDescription(),
      maintainer: this.connectorMaintainer(),
      framework: this.isJavaBasedConnector ? 'JAVA_BASED' : 'LOW_CODE',
      license: this.connectorLicense() || null,
      browseLink: this.devProjectHomepage() || null,
      gitCloneUrl: this.devGitCloneUrl() || null,
      buildFramework: this.devBuildTool() ? this.devBuildTool().toUpperCase() : null,
      pathToProject: this.devProjectFolderPath() || null,
      className: this.devClassName() || null,
      bundleName: this.connectorBundleName() || null,
      version: this.connectorVersion() || null,
      commitTag: this.devCommitTag() || null,
      // midPoint range is set on the edit form; connector range via the "Set up compatibility" modal.
      // Backend leaves the method's midPoint version untouched when these are null, and defaults the
      // connector min version to the connector revision (or 1.0.0).
      midpointMinVersion: null,
      midpointMaxVersion: null,
      connectorVersionFrom: null,
      connectorVersionTo: null,
      connectorCapabilities: this.connectorCapabilities().map(g => ({
        objectClass: g.objectClass,
        capabilityNames: g.capabilityNames
      }))
    };

    this.appService.addConnectorToIntegrationMethod(this.appId, this.versionId, this.revision, payload).subscribe({
      next: () => { this.isSaving.set(false); this.saved.emit(); },
      error: err => {
        this.isSaving.set(false);
        console.error('Add connector failed', err);
        this.saveError.set(
          err?.error?.message || err?.message || 'Failed to add connector. Please try again.'
        );
      }
    });
  }
}
