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
import { ApplicationService } from '../../services/application.service';
import { AuthService, UserRole } from '../../services/auth.service';
import { CapabilityPicker, CapabilityGroup } from '../capability-picker/capability-picker';
import { ImplementationListItem } from '../../models/implementation-list-item.model';

/** Payload sent to updateConnector — also emitted for staging when deferSave is on. */
export interface ConnectorEditPayload {
  displayName: string;
  description: string;
  maintainer: string;
  license: string | null;
  browseLink: string | null;
  supportPortal: string | null;
  gitCloneUrl: string | null;
  buildFramework: string | null;
  pathToProject: string | null;
  className: string | null;
  bundleName: string | null;
  commitTag: string | null;
  connectorCapabilities: { objectClass: string; capabilityNames: string[] }[];
}

@Component({
  selector: 'app-edit-connector-modal',
  standalone: true,
  imports: [CommonModule, FormsModule, CapabilityPicker],
  templateUrl: './edit-connector-modal.html',
  styleUrls: ['./edit-connector-modal.scss']
})
export class EditConnectorModal implements OnInit {
  @Input() appId = '';
  @Input() methodId = '';
  @Input() revision = '';
  @Input({ required: true }) connector!: ImplementationListItem;
  /** When true, the modal does not persist; it emits the edit for the parent to stage. */
  @Input() deferSave = false;
  @Output() close = new EventEmitter<void>();
  @Output() saved = new EventEmitter<void>();
  @Output() stagedEdit = new EventEmitter<ConnectorEditPayload>();

  protected readonly isSaving = signal<boolean>(false);
  protected readonly saveError = signal<string>('');

  protected readonly basicInfoExpanded = signal<boolean>(true);
  protected readonly devBuildExpanded = signal<boolean>(false);

  // ── Basic information & capabilities ──────────────────────
  protected readonly connectorName = signal<string>('');
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
  protected readonly connectorCapabilities = signal<CapabilityGroup[]>([]);
  protected readonly initialCapabilities = signal<CapabilityGroup[]>([]);

  // ── Development & build ───────────────────────────────────
  protected readonly devProjectHomepage = signal<string>('');
  protected readonly devSupportPortal = signal<string>('');
  protected readonly devBuildTool = signal<'maven' | 'gradle' | ''>('');
  protected readonly devGitCloneUrl = signal<string>('');
  protected readonly devCommitTag = signal<string>('');
  protected readonly devProjectFolderPath = signal<string>('');
  protected readonly devClassName = signal<string>('');

  protected isJavaBased = false;

  protected readonly licenseOptions = ['MIT', 'APACHE_2', 'BSD', 'EUPL'];
  protected readonly licenseLabels: Record<string, string> = {
    'MIT': 'MIT', 'APACHE_2': 'Apache 2.0', 'BSD': 'BSD', 'EUPL': 'EUPL 1.2'
  };

  // ── Validation ────────────────────────────────────────────
  protected readonly isGitCloneUrlInvalid = computed(() => {
    const url = this.devGitCloneUrl();
    return !!url && !url.trim().endsWith('.git');
  });

  protected readonly isClassNameInvalid = computed(() => {
    const name = this.devClassName().trim();
    if (!name) return false;
    return name.includes('/') || name.includes('\\') || name.includes(' ') || !name.includes('.');
  });

  protected readonly isValid = computed(() => {
    const base = !!this.connectorName().trim()
      && !!this.connectorMaintainer().trim()
      && !!this.connectorLicense();
    if (!base) return false;
    const devOk = !!this.devGitCloneUrl().trim()
      && !!this.devCommitTag().trim()
      && !this.isGitCloneUrlInvalid();
    const javaOk = !this.isJavaBased
      || (!!this.devBuildTool() && !!this.devClassName().trim() && !this.isClassNameInvalid());
    return devOk && javaOk;
  });

  constructor(
    private appService: ApplicationService,
    private authService: AuthService
  ) {}

  ngOnInit(): void {
    const c = this.connector;
    this.isJavaBased = c.bundleFramework === 'JAVA_BASED';

    this.connectorName.set(c.bundleDisplayName || c.name || '');
    this.connectorVersion.set(c.version ?? '');
    this.connectorMaintainer.set(c.maintainer ?? '');
    this.connectorLicense.set(c.licenseType ?? '');
    this.connectorDescription.set(c.implementationDescription ?? '');
    this.connectorBundleName.set(c.bundleName ?? '');

    this.devProjectHomepage.set(c.browseLink ?? '');
    this.devSupportPortal.set(c.ticketingLink ?? '');
    this.devGitCloneUrl.set(c.gitCloneUrl ?? '');
    this.devCommitTag.set(c.commitTag ?? '');
    this.devProjectFolderPath.set(c.pathToProjectDirectory ?? '');
    this.devClassName.set(c.className ?? '');
    const bf = (c.buildFramework ?? '').toLowerCase();
    this.devBuildTool.set(bf === 'maven' || bf === 'gradle' ? bf as 'maven' | 'gradle' : '');

    const caps: CapabilityGroup[] = (c.objectClassCapabilities ?? []).map(oc => ({
      objectClass: oc.objectName,
      capabilityNames: oc.capabilities ?? []
    }));
    this.initialCapabilities.set(caps);
    this.connectorCapabilities.set(caps);

    this.connectorMaintainer.set(c.maintainer ?? this.authService.currentUser() ?? '');
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

  // ── Maintainer combobox ───────────────────────────────────
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

  // ── License combobox ──────────────────────────────────────
  protected fmtLicense(key: string): string {
    return this.licenseLabels[key] ?? key;
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

  // ── Save ──────────────────────────────────────────────────
  protected save(): void {
    const connectorId = this.connector.connectorId;
    if (connectorId == null) {
      this.saveError.set('This connector cannot be edited (missing identifier).');
      return;
    }
    this.saveError.set('');

    const payload: ConnectorEditPayload = {
      displayName: this.connectorName(),
      description: this.connectorDescription(),
      maintainer: this.connectorMaintainer(),
      license: this.connectorLicense() || null,
      browseLink: this.devProjectHomepage() || null,
      supportPortal: this.devSupportPortal() || null,
      gitCloneUrl: this.devGitCloneUrl() || null,
      buildFramework: this.devBuildTool() ? this.devBuildTool().toUpperCase() : null,
      pathToProject: this.devProjectFolderPath() || null,
      className: this.devClassName() || null,
      bundleName: this.connectorBundleName() || null,
      commitTag: this.devCommitTag() || null,
      connectorCapabilities: this.connectorCapabilities().map(g => ({
        objectClass: g.objectClass,
        capabilityNames: g.capabilityNames
      }))
    };

    // Deferred: hand the edit to the parent to stage; nothing is persisted here.
    if (this.deferSave) {
      this.stagedEdit.emit(payload);
      return;
    }

    this.isSaving.set(true);
    this.appService.updateConnector(this.appId, this.methodId, this.revision, connectorId, payload)
      .subscribe({
        next: () => { this.isSaving.set(false); this.saved.emit(); },
        error: err => {
          this.isSaving.set(false);
          console.error('Update connector failed', err);
          this.saveError.set(
            err?.error?.message || err?.message || 'Failed to save changes. Please try again.'
          );
        }
      });
  }
}
