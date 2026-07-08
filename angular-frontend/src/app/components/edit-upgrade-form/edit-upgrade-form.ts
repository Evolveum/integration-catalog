/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, OnInit, OnDestroy, signal, computed } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin, Observable } from 'rxjs';
import EasyMDE from 'easymde';
import { ApplicationService } from '../../services/application.service';
import { AuthService } from '../../services/auth.service';
import { PageHeader } from '../page-header/page-header';
import { CapabilityPicker, CapabilityGroup } from '../capability-picker/capability-picker';
import { AddConnectorForm } from '../add-connector-form/add-connector-form';
import { EditConnectorModal } from '../edit-connector-modal/edit-connector-modal';
import { ImplementationListItem } from '../../models/implementation-list-item.model';
import { hasLogoDetail, MidpointVersion, ObjectClassCapability } from '../../models/application-detail.model';

@Component({
  selector: 'app-edit-upgrade-form',
  standalone: true,
  imports: [CommonModule, FormsModule, PageHeader, CapabilityPicker, AddConnectorForm, EditConnectorModal],
  templateUrl: './edit-upgrade-form.html',
  styleUrls: ['./edit-upgrade-form.scss']
})
export class EditUpgradeForm implements OnInit, OnDestroy {
  protected readonly loading = signal<boolean>(true);
  protected readonly showAddConnector = signal<boolean>(false);
  // protected readonly licenseExpanded = signal<boolean>(false);
  protected readonly appId = signal<string>('');
  protected readonly appName = signal<string>('');
  protected readonly appHasLogo = signal<boolean>(false);
  protected readonly logoLoadError = signal<boolean>(false);
  protected readonly versionId = signal<string>('');

  // Form fields
  protected readonly methodName = signal<string>('');
  protected readonly methodVersion = signal<string>('');
  protected readonly methodLifecycleState = signal<string | null>(null);
  protected readonly methodDescription = signal<string>('');
  protected readonly methodTypes = signal<string[]>([]);
  protected readonly imCapabilities = signal<CapabilityGroup[]>([]);
  protected readonly initialCapabilities = signal<CapabilityGroup[]>([]);

  // Supported midPoint version range (loaded from DB, editable)
  protected readonly midpointVersions = signal<MidpointVersion[]>([]);
  protected readonly midpointMinVersionId = signal<number | null>(null);
  protected readonly midpointMaxVersionId = signal<number | null>(null);
  protected readonly isMidpointVersionRangeInvalid = computed(() => {
    const minId = this.midpointMinVersionId();
    const maxId = this.midpointMaxVersionId();
    if (!minId || !maxId) return false;
    const versions = this.midpointVersions();
    const minIndex = versions.findIndex(v => v.id === minId);
    const maxIndex = versions.findIndex(v => v.id === maxId);
    return maxIndex < minIndex;
  });
  // Mirrors the publish form's compatibility gate: "From" is required and the range must be valid.
  protected readonly isMidpointRangeValid = computed(() =>
    this.midpointMinVersionId() !== null && !this.isMidpointVersionRangeInvalid()
  );

  // Tutorial content
  protected readonly methodTutorial = signal<string>('');

  // Tutorial files (multiple per integration method)
  protected readonly tutorialDragOver = signal<boolean>(false);
  protected readonly tutorialFiles = signal<{ name: string; file?: File; isNew: boolean }[]>([]);
  private readonly initialFileNames = signal<string[]>([]);

  // License type display labels
  private readonly licenseLabels: Record<string, string> = {
    'MIT': 'MIT', 'APACHE_2': 'Apache 2.0', 'BSD': 'BSD', 'EUPL': 'EUPL 1.2'
  };

  // Connectors
  protected readonly connectors = signal<ImplementationListItem[]>([]);
  protected readonly connectorCapsExpanded = signal<Set<string>>(new Set());
  protected readonly editingConnector = signal<ImplementationListItem | null>(null);
  protected readonly pendingDeleteConnector = signal<ImplementationListItem | null>(null);

  private easyMde: EasyMDE | null = null;
  private editorPreviewActivated = false;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private applicationService: ApplicationService,
    private authService: AuthService
  ) {}

  ngOnInit(): void {
    const aId = this.route.snapshot.paramMap.get('appId') ?? '';
    const vId = this.route.snapshot.paramMap.get('versionId') ?? '';
    const rev = this.route.snapshot.paramMap.get('revision') ?? '';
    this.appId.set(aId);
    this.versionId.set(vId);

    this.applicationService.getMidpointVersions().subscribe({
      next: (versions) => this.midpointVersions.set(versions),
      error: () => this.midpointVersions.set([])
    });

    this.applicationService.getById(aId).subscribe({
      next: (app) => {
        this.appName.set(app.displayName);
        this.appHasLogo.set(hasLogoDetail(app));
        // A method can have several revisions sharing one id; open the one named in the route,
        // falling back to the latest if the revision is missing (e.g. an old link).
        const methods = (app.integrationMethods ?? []).filter(m => m.id === vId);
        const ver = (rev ? methods.find(m => m.revision === rev) : undefined)
          ?? methods
            .sort((a, b) => (a.revision ?? '').localeCompare(b.revision ?? '', undefined, { numeric: true }))
            .at(-1);
        if (ver) {
          this.methodName.set(ver.displayName ?? '');
          this.methodVersion.set(ver.revision ?? '');
          this.methodLifecycleState.set(ver.lifecycleState ?? null);
          this.methodDescription.set(ver.description ?? '');
          this.methodTypes.set(ver.integMethodTypes ?? []);
          this.midpointMinVersionId.set(ver.midpointMinVersionId);
          this.midpointMaxVersionId.set(ver.midpointMaxVersionId);
          this.methodTutorial.set(ver.tutorial ?? '');
          if (this.easyMde && ver.tutorial) {
            this.easyMde.value(ver.tutorial);
            if (!this.editorPreviewActivated) {
              EasyMDE.togglePreview(this.easyMde);
              this.editorPreviewActivated = true;
            }
          }
          this.loadTutorialFiles(aId, vId, ver.revision ?? '');
          this.initialCapabilities.set(
            (ver.objectClassCapabilities ?? []).map(oc => ({
              objectClass: oc.objectName,
              capabilityNames: oc.capabilities ?? []
            }))
          );
          this.loadConnectors(aId, vId, ver.revision ?? '');
        } else {
          this.finishLoading();
        }
      },
      error: () => this.finishLoading()
    });
  }

  private loadTutorialFiles(appId: string, methodId: string, revision: string): void {
    this.applicationService.listTutorialFiles(appId, methodId, revision).subscribe({
      next: (names) => {
        this.tutorialFiles.set(names.map(n => ({ name: n, isNew: false })));
        this.initialFileNames.set(names);
      },
      error: () => {
        this.tutorialFiles.set([]);
        this.initialFileNames.set([]);
      }
    });
  }

  private loadConnectors(appId: string, methodId: string, revision: string): void {
    this.applicationService.getConnectorsForIntegrationMethod(appId, methodId, revision).subscribe({
      next: (connectors) => {
        this.connectors.set(connectors);
        this.finishLoading();
      },
      error: () => this.finishLoading()
    });
  }

  private finishLoading(): void {
    this.loading.set(false);
    setTimeout(() => this.initEditor(), 50);
  }

  ngOnDestroy(): void {
    if (this.easyMde) {
      this.easyMde.toTextArea();
      this.easyMde = null;
    }
  }

  private initEditor(): void {
    const el = document.getElementById('edit-tutorial-editor') as HTMLTextAreaElement | null;
    if (!el || this.easyMde) return;
    this.easyMde = new EasyMDE({
      element: el,
      spellChecker: false,
      autosave: { enabled: false, uniqueId: 'edit-tutorial-' + this.versionId() },
      toolbar: ['bold', 'italic', 'strikethrough', '|',
                'heading-1', 'heading-2', '|',
                'unordered-list', 'ordered-list', '|',
                'link', '|', 'preview', 'side-by-side'],
      placeholder: 'Write your integration tutorial here...',
    });
    if (this.methodTutorial()) {
      this.easyMde.value(this.methodTutorial());
      EasyMDE.togglePreview(this.easyMde);
      this.editorPreviewActivated = true;
    }
  }

  protected onImCapabilitiesChange(caps: CapabilityGroup[]): void {
    this.imCapabilities.set(caps);
  }

  protected onTutorialFileDrop(event: DragEvent): void {
    event.preventDefault();
    this.tutorialDragOver.set(false);
    if (event.dataTransfer?.files) this.addFiles(event.dataTransfer.files);
  }

  protected onTutorialFileSelect(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files) this.addFiles(input.files);
    input.value = '';
  }

  private addFiles(files: FileList): void {
    const existing = new Set(this.tutorialFiles().map(f => f.name));
    const toAdd: { name: string; file: File; isNew: boolean }[] = [];
    for (let i = 0; i < files.length; i++) {
      const f = files[i];
      if (f && !existing.has(f.name)) {
        toAdd.push({ name: f.name, file: f, isNew: true });
        existing.add(f.name);
      }
    }
    if (toAdd.length) this.tutorialFiles.update(list => [...list, ...toAdd]);
  }

  protected tutorialFileUrl(name: string): string {
    return this.applicationService.getTutorialFileUrl(this.appId(), this.versionId(), this.methodVersion(), name);
  }

  protected removeTutorialFile(i: number): void {
    this.tutorialFiles.update(list => list.filter((_, idx) => idx !== i));
  }

  protected toggleConnectorCaps(key: string): void {
    this.connectorCapsExpanded.update(s => {
      const ns = new Set(s);
      ns.has(key) ? ns.delete(key) : ns.add(key);
      return ns;
    });
  }

  protected isCapsExpanded(key: string): boolean {
    return this.connectorCapsExpanded().has(key);
  }

  // Connector detail sub-sections (Repository, Framework, Source, Implementation).
  // Tracks the COLLAPSED ones so every section starts expanded (matches the IM-detail design).
  protected readonly collapsedSections = signal<Set<string>>(new Set());

  protected toggleSection(key: string): void {
    this.collapsedSections.update(s => {
      const ns = new Set(s);
      ns.has(key) ? ns.delete(key) : ns.add(key);
      return ns;
    });
  }

  protected isSectionExpanded(key: string): boolean {
    return !this.collapsedSections().has(key);
  }

  protected fmtLicense(key: string | null | undefined): string {
    if (!key) return '—';
    return this.licenseLabels[key] ?? key;
  }

  /** Turn a build-framework enum (e.g. "MAVEN") into a friendly label ("Maven"). */
  protected formatBuildTool(value: string): string {
    if (!value) return '—';
    return value.charAt(0).toUpperCase() + value.slice(1).toLowerCase();
  }

  /** Append " (you)" when the maintainer matches the logged-in user. */
  protected formatMaintainer(maintainer: string): string {
    if (!maintainer) return '—';
    const user = this.authService.currentUser();
    return user && user.trim().toLowerCase() === maintainer.trim().toLowerCase()
      ? `${maintainer} (you)`
      : maintainer;
  }

  protected formatCapabilityText(text: string): string {
    if (!text) return '';
    const withSpaces = text.replace(/_/g, ' ').toLowerCase();
    return withSpaces.charAt(0).toUpperCase() + withSpaces.slice(1);
  }

  /** A connector's object-class capabilities, with the Global class first when present. */
  protected orderedConnectorCaps(caps: ObjectClassCapability[] | null | undefined): ObjectClassCapability[] {
    return [...(caps ?? [])].sort((a, b) =>
      (a.objectName === 'Global' ? 0 : 1) - (b.objectName === 'Global' ? 0 : 1)
    );
  }

  protected getLogoUrl(): string {
    return this.applicationService.getLogoUrl(this.appId());
  }

  protected onLogoError(): void {
    this.logoLoadError.set(true);
  }

  protected showLogo(): boolean {
    return this.appHasLogo() && !this.logoLoadError();
  }

  protected goToCatalog(): void {
    this.router.navigate(['/applications']);
  }

  protected cancel(): void {
    this.router.navigate(['/applications', this.appId()]);
  }

  protected onConnectorSaved(): void {
    this.showAddConnector.set(false);
    this.loadConnectors(this.appId(), this.versionId(), this.methodVersion());
  }

  protected openEditConnector(connector: ImplementationListItem): void {
    this.editingConnector.set(connector);
  }

  protected closeEditConnector(): void {
    this.editingConnector.set(null);
  }

  protected onConnectorEdited(): void {
    this.editingConnector.set(null);
    this.loadConnectors(this.appId(), this.versionId(), this.methodVersion());
  }

  protected deleteConnector(connector: ImplementationListItem): void {
    this.pendingDeleteConnector.set(connector);
  }

  protected cancelDeleteConnector(): void {
    this.pendingDeleteConnector.set(null);
  }

  protected confirmDeleteConnector(): void {
    const connector = this.pendingDeleteConnector();
    if (!connector || connector.connectorId == null) {
      this.pendingDeleteConnector.set(null);
      return;
    }
    this.applicationService.deleteConnector(this.appId(), this.versionId(), this.methodVersion(), connector.connectorId).subscribe({
      next: () => {
        this.pendingDeleteConnector.set(null);
        this.loadConnectors(this.appId(), this.versionId(), this.methodVersion());
      },
      error: (err) => {
        console.error('Failed to delete connector', err);
        this.pendingDeleteConnector.set(null);
      }
    });
  }

  // ── "Set up connector compatibility" modal (connector version range) ──────────
  protected readonly compatConnector = signal<ImplementationListItem | null>(null);
  protected readonly compatFrom = signal<string>('');
  protected readonly compatTo = signal<string>('');
  protected readonly compatInfoDismissed = signal<boolean>(false);
  protected readonly compatSaving = signal<boolean>(false);

  /** A version is valid when it is in X.Y.Z form (three dot-separated numbers). */
  private isVersionFormatValid(v: string): boolean {
    return /^\d+\.\d+\.\d+$/.test(v.trim());
  }

  private compareVersions(a: string, b: string): number {
    const pa = a.trim().split('.').map(n => parseInt(n, 10));
    const pb = b.trim().split('.').map(n => parseInt(n, 10));
    for (let i = 0; i < 3; i++) {
      if ((pa[i] ?? 0) !== (pb[i] ?? 0)) return (pa[i] ?? 0) - (pb[i] ?? 0);
    }
    return 0;
  }

  protected readonly isCompatFromValid = computed(() => this.isVersionFormatValid(this.compatFrom()));

  protected readonly isCompatToValid = computed(() => {
    const to = this.compatTo().trim();
    return !to || this.isVersionFormatValid(to);
  });

  protected readonly isCompatRangeInvalid = computed(() => {
    const to = this.compatTo().trim();
    if (!this.isCompatFromValid() || !to || !this.isVersionFormatValid(to)) return false;
    return this.compareVersions(to, this.compatFrom()) < 0;
  });

  protected readonly isCompatValid = computed(() =>
    this.isCompatFromValid() && this.isCompatToValid() && !this.isCompatRangeInvalid()
  );

  protected openCompatibility(connector: ImplementationListItem): void {
    this.compatConnector.set(connector);
    // Prefill strictly from the IM↔connector link's version range (connector_minVersion /
    // connector_maxVersion) — never the connector bundle version or the midPoint range.
    this.compatFrom.set(connector.connectorMinVersion ?? '');
    this.compatTo.set(connector.connectorMaxVersion ?? '');
    this.compatInfoDismissed.set(false);
  }

  protected closeCompatibility(): void {
    this.compatConnector.set(null);
  }

  protected applyCompatibility(): void {
    const connector = this.compatConnector();
    if (!connector || connector.connectorId == null || !this.isCompatValid()) return;
    const from = this.compatFrom().trim();
    const to = this.compatTo().trim() || null;
    this.compatSaving.set(true);
    this.applicationService.updateConnectorCompatibility(
      this.appId(), this.versionId(), this.methodVersion(), connector.connectorId,
      { connectorVersionFrom: from, connectorVersionTo: to }
    ).subscribe({
      next: () => {
        // Reflect the change locally so the card shows the new range without a full reload.
        this.connectors.update(list => list.map(c =>
          c.connectorId === connector.connectorId
            ? { ...c, connectorMinVersion: from, connectorMaxVersion: to }
            : c
        ));
        this.compatSaving.set(false);
        this.compatConnector.set(null);
      },
      error: (err) => {
        console.error('Failed to update connector compatibility', err);
        this.compatSaving.set(false);
      }
    });
  }

  protected isInReview(): boolean {
    return this.methodLifecycleState() === 'IN_REVIEW';
  }
  
  // A minor "Save" shows a confirmation modal; a major "Save as new version" shows an upgrade modal.
  // Either modal's "Go to catalog" then leaves to the app detail.
  protected readonly showSavedModal = signal<boolean>(false);
  protected readonly showNewVersionModal = signal<boolean>(false);

  protected closeSavedModal(): void {
    this.showSavedModal.set(false);
  }

  protected closeNewVersionModal(): void {
    this.showNewVersionModal.set(false);
  }

  /** Version badge ("v1", "v2", …) derived from the major part of the current revision. */
  protected versionBadge(revision: string): string {
    const major = parseInt((revision ?? '').split('.')[0], 10);
    return isNaN(major) ? 'v1' : `v${major}`;
  }

  // Modal action: return to the application-detail of the app this IM belongs to
  // (distinct from the breadcrumb's goToCatalog(), which opens the applications list).
  protected savedGoToCatalog(): void {
    this.showSavedModal.set(false);
    this.showNewVersionModal.set(false);
    this.router.navigate(['/applications', this.appId()]);
  }

  private afterSave(major: boolean): void {
    if (major) {
      this.showNewVersionModal.set(true);
    } else {
      this.showSavedModal.set(true);
    }
  }

  protected save(): void {
    this.doSave(false);
  }

  protected saveAsNewVersion(): void {
    this.doSave(true);
  }

  private doSave(major: boolean): void {
    const tutorial = this.easyMde ? this.easyMde.value() : this.methodTutorial();
    const capabilities = this.imCapabilities().length > 0
      ? this.imCapabilities()
      : this.initialCapabilities();

    const newFiles = this.tutorialFiles().filter(f => f.isNew && f.file).map(f => f.file!);
    const keptNames = this.tutorialFiles().filter(f => !f.isNew).map(f => f.name);
    const removedNames = this.initialFileNames().filter(n => !keptNames.includes(n));

    this.applicationService.editIntegrationMethod(
      this.appId(),
      this.versionId(),
      this.methodVersion(),
      {
        displayName: this.methodName(),
        description: this.methodDescription(),
        tutorial,
        capabilities: capabilities.map(g => ({ objectClass: g.objectClass, capabilityNames: g.capabilityNames })),
        removeFile: false,
        // "Save" (major=false) is a minor in-place bump; "Save as new version" (major=true) is a major bump.
        minorBump: !major,
        midpointMinVersion: this.midpointMinVersionId(),
        midpointMaxVersion: this.midpointMaxVersionId()
      }
    ).subscribe({
      next: (savedRevision) => {
        this.methodVersion.set(savedRevision);
        // The new revision starts with the previous revision's files copied forward by the backend;
        // here we delete the files the user removed and upload the ones they added.
        const ops: Observable<void>[] = [
          ...removedNames.map(n => this.applicationService.deleteTutorialFile(this.appId(), this.versionId(), savedRevision, n)),
          ...newFiles.map(f => this.applicationService.uploadTutorialFile(this.appId(), this.versionId(), savedRevision, f))
        ];
        if (ops.length === 0) {
          this.afterSave(major);
          return;
        }
        forkJoin(ops).subscribe({
          next: () => this.afterSave(major),
          error: (err) => {
            console.error('Tutorial file sync failed', err);
            this.afterSave(major);
          }
        });
      },
      error: (err) => console.error('Save failed', err)
    });
  }
}
