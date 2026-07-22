/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, OnInit, OnDestroy, signal, computed } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { concat, forkJoin, of, Observable } from 'rxjs';
import { toArray } from 'rxjs/operators';
import EasyMDE from 'easymde';
import { ApplicationService } from '../../services/application.service';
import { AuthService } from '../../services/auth.service';
import { PageHeader } from '../page-header/page-header';
import { CapabilityPicker, CapabilityGroup } from '../capability-picker/capability-picker';
import { AddConnectorForm, StagedConnector } from '../add-connector-form/add-connector-form';
import { EditConnectorModal, ConnectorEditPayload } from '../edit-connector-modal/edit-connector-modal';
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
  // Set once a connector is added directly to a mutable (in-review/rejected) revision this session.
  protected readonly connectorAdded = signal<boolean>(false);
  // Connectors added while on a PUBLISHED version: held here, unpersisted, until the user saves a new
  // version. Nothing touches the backend (and no version is created) unless/until that save happens.
  protected readonly stagedConnectors = signal<StagedConnector[]>([]);
  protected readonly hasStagedConnectors = computed(() => this.stagedConnectors().length > 0);
  // protected readonly licenseExpanded = signal<boolean>(false);
  protected readonly appId = signal<string>('');
  protected readonly appName = signal<string>('');
  protected readonly appHasLogo = signal<boolean>(false);
  protected readonly logoLoadError = signal<boolean>(false);
  protected readonly versionId = signal<string>('');
  // Surfaced when a Save / Save-as-new-version request fails, so the failure isn't silent.
  protected readonly saveError = signal<string>('');
  protected readonly isSaving = signal<boolean>(false);

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

  // Staged connector changes to existing connectors — held in memory and applied only on
  // Save / Save as new version (keyed by connectorId), never persisted immediately.
  private readonly stagedEdits = signal<Map<number, ConnectorEditPayload>>(new Map());
  private readonly stagedDeletes = signal<Set<number>>(new Set());
  private readonly stagedCompat = signal<Map<number, { from: string; to: string | null }>>(new Map());

  // Connector list the user sees: backend connectors minus staged deletes, with staged edits merged in.
  protected readonly displayConnectors = computed<ImplementationListItem[]>(() => {
    const deletes = this.stagedDeletes();
    const edits = this.stagedEdits();
    return this.connectors()
      .filter(c => c.connectorId == null || !deletes.has(c.connectorId))
      .map(c => (c.connectorId != null && edits.has(c.connectorId))
        ? this.mergeConnectorEdit(c, edits.get(c.connectorId)!)
        : c);
  });

  protected readonly hasPendingConnectorChanges = computed(() =>
    this.stagedConnectors().length > 0 || this.stagedEdits().size > 0 ||
    this.stagedDeletes().size > 0 || this.stagedCompat().size > 0
  );

  // A staged edit changing a connector's identity — version, className or bundleName, the triple
  // that must match the Maven artifact — or an add/delete/compatibility change SUGGESTS saving as
  // a new version, but never forces it: Save stays enabled, and the Save vs "Save as new version"
  // choice is simply the author's signal to the reviewer of how they want the change treated
  // (e.g. a wording fix may deliberately stay on the same IM version). The reviewer decides.
  protected readonly hasMajorConnectorChanges = computed(() => {
    if (this.stagedConnectors().length > 0 || this.stagedDeletes().size > 0 || this.stagedCompat().size > 0) {
      return true;
    }
    const byId = new Map(this.connectors()
      .filter(c => c.connectorId != null)
      .map(c => [c.connectorId!, c] as const));
    return Array.from(this.stagedEdits().entries()).some(([id, p]) => {
      const original = byId.get(id);
      return !original || this.isMajorConnectorEdit(original, p);
    });
  });

  // Purely informational (see hasMajorConnectorChanges): shows the footer hint on a published
  // revision, where both save buttons are offered. On a draft revision (only "Save" is shown —
  // see isDraftState) the hint is pointless, so this stays false there.
  protected readonly suggestsNewVersionForConnectorChange = computed(() =>
    this.hasMajorConnectorChanges() && !this.isDraftState()
  );

  /** Whether a staged edit changes the connector identity (version, className, bundleName). */
  private isMajorConnectorEdit(c: ImplementationListItem, p: ConnectorEditPayload): boolean {
    const norm = (v: string | null | undefined): string => (v ?? '').trim();
    return norm(p.version) !== norm(c.version)
      || norm(p.className) !== norm(c.className)
      || norm(p.bundleName) !== norm(c.bundleName);
  }

  protected isConnectorPending(connectorId: number | null): boolean {
    if (connectorId == null) return false;
    return this.stagedEdits().has(connectorId) || this.stagedCompat().has(connectorId);
  }

  /** Overlay a staged edit payload onto a connector so the card previews the pending values. */
  private mergeConnectorEdit(c: ImplementationListItem, p: ConnectorEditPayload): ImplementationListItem {
    return {
      ...c,
      name: p.displayName,
      displayName: p.displayName,
      bundleDisplayName: p.displayName,
      implementationDescription: p.description,
      maintainer: p.maintainer,
      // The staged maintainer's organization is only known locally for the current user;
      // other picks (superuser) preview plain until the save reloads server data.
      maintainerOrganization:
        p.maintainer === c.maintainer ? c.maintainerOrganization
          : p.maintainer === this.authService.currentUser() ? this.authService.currentOrganizationName()
          : null,
      licenseType: p.license ?? '',
      browseLink: p.browseLink ?? '',
      ticketingLink: p.supportPortal ?? '',
      gitCloneUrl: p.gitCloneUrl ?? '',
      buildFramework: p.buildFramework ?? '',
      pathToProjectDirectory: p.pathToProject ?? '',
      className: p.className ?? '',
      bundleName: p.bundleName ?? '',
      commitTag: p.commitTag ?? '',
      version: p.version ?? c.version,
      objectClassCapabilities: p.connectorCapabilities.map(g => ({
        objectName: g.objectClass,
        capabilities: g.capabilityNames
      }))
    };
  }

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

  /** A maintainer belonging to an organization is shown as "org (username)". */
  protected formatMaintainer(maintainer: string, maintainerOrganization?: string | null): string {
    if (!maintainer) return '—';
    return maintainerOrganization ? `${maintainerOrganization} (${maintainer})` : maintainer;
  }

  /** Org of a locally staged maintainer: only resolvable when it is the current user. */
  protected localMaintainerOrg(maintainer: string): string | null {
    return maintainer === this.authService.currentUser()
      ? this.authService.currentOrganizationName()
      : null;
  }

  /**
   * Whether the current user may edit this connector's content. A connector is gated on its
   * own maintainer (not the IM's): the IM maintainer must not edit connectors maintained by
   * someone else. The server enforces the same rule. (Compatibility/delete/add remain IM-level
   * actions, available to anyone who could open this edit form.)
   */
  protected canEditConnector(c: ImplementationListItem): boolean {
    return this.authService.canEdit(null, null, c.maintainer, c.maintainerOrganization);
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

  protected openAddConnector(): void {
    // The add-connector form replaces the edit form in the DOM, tearing out the tutorial textarea.
    // Dispose the EasyMDE instance first (keeping its content) so we can cleanly rebuild it on return.
    this.teardownEditor();
    this.showAddConnector.set(true);
  }

  protected closeAddConnector(): void {
    this.showAddConnector.set(false);
    // The tutorial textarea is back in the DOM now; rebuild the editor over it.
    setTimeout(() => this.initEditor(), 50);
  }

  protected onConnectorSaved(newRevision: string): void {
    this.showAddConnector.set(false);
    this.connectorAdded.set(true);
    // The backend returns the revision the connector landed on. Adding to a published version forks a
    // fresh in-review draft (a new revision); adding to an existing draft returns the same revision.
    // When it changed, move the form onto the draft so the connector shows and Save edits the draft.
    // Method-level form fields are left as-is so any in-progress edits aren't lost.
    if (newRevision && newRevision !== this.methodVersion()) {
      this.methodVersion.set(newRevision);
      this.methodLifecycleState.set('IN_REVIEW');
      this.loadTutorialFiles(this.appId(), this.versionId(), newRevision);
    }
    this.loadConnectors(this.appId(), this.versionId(), this.methodVersion());
    // loadConnectors -> finishLoading rebuilds the editor, but rebuild explicitly too so the tutorial
    // field never shows as a bare textarea.
    setTimeout(() => this.initEditor(), 50);
  }

  /** A published (ACTIVE) revision is immutable, so connectors added to it must be staged, not persisted. */
  protected isPublished(): boolean {
    return this.methodLifecycleState() === 'ACTIVE';
  }

  protected onConnectorStaged(sc: StagedConnector): void {
    // Hold the connector in the form. It is persisted only when the user saves a new version; until
    // then the published revision is untouched and no new version exists.
    this.stagedConnectors.update(list => [...list, sc]);
    this.showAddConnector.set(false);
    setTimeout(() => this.initEditor(), 50);
  }

  protected removeStagedConnector(index: number): void {
    this.stagedConnectors.update(list => list.filter((_, i) => i !== index));
  }

  /**
   * Capture the current tutorial content and dispose the EasyMDE instance before its textarea is
   * removed from the DOM. Without this, the stale instance reference blocks re-initialisation and
   * the field renders as an unstyled textarea when the edit form comes back.
   */
  private teardownEditor(): void {
    if (this.easyMde) {
      this.methodTutorial.set(this.easyMde.value());
      this.easyMde.toTextArea();
      this.easyMde = null;
    }
  }

  protected openEditConnector(connector: ImplementationListItem): void {
    this.editingConnector.set(connector);
  }

  protected closeEditConnector(): void {
    this.editingConnector.set(null);
  }

  /** Stage a connector edit; it is persisted only on Save / Save as new version. */
  protected onConnectorEditStaged(payload: ConnectorEditPayload): void {
    const connector = this.editingConnector();
    this.editingConnector.set(null);
    if (!connector || connector.connectorId == null) return;
    const id = connector.connectorId;
    this.stagedEdits.update(m => new Map(m).set(id, payload));
  }

  protected deleteConnector(connector: ImplementationListItem): void {
    this.pendingDeleteConnector.set(connector);
  }

  protected cancelDeleteConnector(): void {
    this.pendingDeleteConnector.set(null);
  }

  /** Stage the deletion; the connector is removed from the backend only on save. */
  protected confirmDeleteConnector(): void {
    const connector = this.pendingDeleteConnector();
    this.pendingDeleteConnector.set(null);
    if (!connector || connector.connectorId == null) return;
    const id = connector.connectorId;
    this.stagedDeletes.update(s => new Set(s).add(id));
    // A deleted connector shouldn't carry pending edits/compat.
    this.stagedEdits.update(m => { const n = new Map(m); n.delete(id); return n; });
    this.stagedCompat.update(m => { const n = new Map(m); n.delete(id); return n; });
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
    // Prefill from a staged compatibility change if one exists, else from the IM↔connector link's
    // range (connector_minVersion / connector_maxVersion) — never the bundle or midPoint range.
    const staged = connector.connectorId != null ? this.stagedCompat().get(connector.connectorId) : undefined;
    this.compatFrom.set(staged ? staged.from : (connector.connectorMinVersion ?? ''));
    this.compatTo.set(staged ? (staged.to ?? '') : (connector.connectorMaxVersion ?? ''));
    this.compatInfoDismissed.set(false);
  }

  protected closeCompatibility(): void {
    this.compatConnector.set(null);
  }

  /** Stage the compatibility range; it is persisted only on Save / Save as new version. */
  protected applyCompatibility(): void {
    const connector = this.compatConnector();
    if (!connector || connector.connectorId == null || !this.isCompatValid()) return;
    const id = connector.connectorId;
    const from = this.compatFrom().trim();
    const to = this.compatTo().trim() || null;
    this.stagedCompat.update(m => new Map(m).set(id, { from, to }));
    this.compatConnector.set(null);
  }

  protected isInReview(): boolean {
    return this.methodLifecycleState() === 'IN_REVIEW';
  }

  /**
   * Draft (non-published) states editable in place with only "Save" — no "Save as new version".
   * REJECTED is treated like IN_REVIEW here so a rejected method is fixed and resubmitted via Save.
   * REVIEWING too: only a superuser (the reviewer) can open this form then, and their fixes must
   * land on the revision under review, not fork a new draft.
   */
  protected isDraftState(): boolean {
    const state = this.methodLifecycleState();
    return state === 'IN_REVIEW' || state === 'REJECTED' || state === 'REVIEWING';
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

  protected dismissSaveError(): void {
    this.saveError.set('');
  }

  protected save(): void {
    this.doSave(false);
  }

  protected saveAsNewVersion(): void {
    this.doSave(true);
  }

  private doSave(major: boolean): void {
    if (this.isSaving()) { return; }
    this.saveError.set('');
    this.isSaving.set(true);
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
        // All staged connector changes are persisted only now, onto the freshly created revision
        // (connector ids are carried forward by the backend fork). Run them sequentially since they
        // all mutate the same method row: adds → edits → compatibility → deletes.
        const adds = this.stagedConnectors();
        const edits = Array.from(this.stagedEdits().entries());
        const compat = Array.from(this.stagedCompat().entries());
        const deletes = Array.from(this.stagedDeletes());
        const connectorOps: Observable<unknown>[] = [
          ...adds.map(sc =>
            this.applicationService.addConnectorToIntegrationMethod(this.appId(), this.versionId(), savedRevision, sc.payload)),
          ...edits.map(([connectorId, payload]) =>
            this.applicationService.updateConnector(this.appId(), this.versionId(), savedRevision, connectorId, payload)),
          ...compat.map(([connectorId, range]) =>
            this.applicationService.updateConnectorCompatibility(this.appId(), this.versionId(), savedRevision, connectorId,
              { connectorVersionFrom: range.from, connectorVersionTo: range.to })),
          ...deletes.map(connectorId =>
            this.applicationService.deleteConnector(this.appId(), this.versionId(), savedRevision, connectorId))
        ];
        const connectors$: Observable<unknown[]> = connectorOps.length
          ? concat(...connectorOps).pipe(toArray())
          : of<unknown[]>([]);

        connectors$.subscribe({
          next: () => {
            this.stagedConnectors.set([]);
            this.stagedEdits.set(new Map());
            this.stagedCompat.set(new Map());
            this.stagedDeletes.set(new Set());
            // The new revision starts with the previous revision's files copied forward by the backend;
            // here we delete the files the user removed and upload the ones they added.
            const ops: Observable<void>[] = [
              ...removedNames.map(n => this.applicationService.deleteTutorialFile(this.appId(), this.versionId(), savedRevision, n)),
              ...newFiles.map(f => this.applicationService.uploadTutorialFile(this.appId(), this.versionId(), savedRevision, f))
            ];
            if (ops.length === 0) {
              this.isSaving.set(false);
              this.afterSave(major);
              return;
            }
            forkJoin(ops).subscribe({
              next: () => { this.isSaving.set(false); this.afterSave(major); },
              error: (err) => {
                console.error('Tutorial file sync failed', err);
                this.isSaving.set(false);
                this.afterSave(major);
              }
            });
          },
          error: (err) => {
            console.error('Persisting staged connector changes failed', err);
            this.isSaving.set(false);
            this.saveError.set(
              err?.error?.message || err?.error || err?.message ||
              'The new version was created, but applying a staged connector change failed. Please review the connectors.'
            );
          }
        });
      },
      error: (err) => {
        console.error('Save failed', err);
        this.isSaving.set(false);
        this.saveError.set(
          err?.error?.message || err?.error || err?.message ||
          'Saving failed. Please try again — if it keeps failing, reload the page and retry.'
        );
      }
    });
  }
}
