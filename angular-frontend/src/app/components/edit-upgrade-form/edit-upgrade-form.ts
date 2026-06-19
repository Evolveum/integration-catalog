/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, OnInit, OnDestroy, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin, Observable } from 'rxjs';
import EasyMDE from 'easymde';
import { ApplicationService } from '../../services/application.service';
import { PageHeader } from '../page-header/page-header';
import { CapabilityPicker, CapabilityGroup } from '../capability-picker/capability-picker';
import { AddConnectorForm } from '../add-connector-form/add-connector-form';
import { EditConnectorModal } from '../edit-connector-modal/edit-connector-modal';
import { ImplementationListItem } from '../../models/implementation-list-item.model';
import { hasLogoDetail } from '../../models/application-detail.model';

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

  private easyMde: EasyMDE | null = null;
  private editorPreviewActivated = false;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private applicationService: ApplicationService
  ) {}

  ngOnInit(): void {
    const aId = this.route.snapshot.paramMap.get('appId') ?? '';
    const vId = this.route.snapshot.paramMap.get('versionId') ?? '';
    const rev = this.route.snapshot.paramMap.get('revision') ?? '';
    this.appId.set(aId);
    this.versionId.set(vId);

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

  protected fmtLicense(key: string | null | undefined): string {
    if (!key) return '—';
    return this.licenseLabels[key] ?? key;
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

  protected isInReview(): boolean {
    return this.methodLifecycleState() === 'IN_REVIEW';
  }
  
  protected save(): void {
    this.doSave(false);
  }

  protected saveAsNewVersion(): void {
    this.doSave(true);
  }

  private bumpVersion(version: string, major: boolean): string {
    const [maj, min] = version.split('.').map(Number);
    return major ? `${maj + 1}.1` : `${maj}.${min + 1}`;
  }

  private doSave(major: boolean): void {
    const tutorial = this.easyMde ? this.easyMde.value() : this.methodTutorial();
    const capabilities = this.imCapabilities().length > 0
      ? this.imCapabilities()
      : this.initialCapabilities();

    const newFiles = this.tutorialFiles().filter(f => f.isNew && f.file).map(f => f.file!);
    const keptNames = this.tutorialFiles().filter(f => !f.isNew).map(f => f.name);
    const removedNames = this.initialFileNames().filter(n => !keptNames.includes(n));
    const newRevision = this.bumpVersion(this.methodVersion(), major);

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
        newRevision
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
          this.router.navigate(['/applications', this.appId()]);
          return;
        }
        forkJoin(ops).subscribe({
          next: () => this.router.navigate(['/applications', this.appId()]),
          error: (err) => {
            console.error('Tutorial file sync failed', err);
            this.router.navigate(['/applications', this.appId()]);
          }
        });
      },
      error: (err) => console.error('Save failed', err)
    });
  }
}
