/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, OnInit, OnDestroy, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import EasyMDE from 'easymde';
import { ApplicationService } from '../../services/application.service';
import { PageHeader } from '../page-header/page-header';
import { CapabilityPicker, CapabilityGroup } from '../capability-picker/capability-picker';
import { ImplementationListItem } from '../../models/implementation-list-item.model';
import { hasLogoDetail } from '../../models/application-detail.model';

@Component({
  selector: 'app-edit-upgrade-form',
  standalone: true,
  imports: [CommonModule, FormsModule, PageHeader, CapabilityPicker],
  templateUrl: './edit-upgrade-form.html',
  styleUrls: ['./edit-upgrade-form.scss']
})
export class EditUpgradeForm implements OnInit, OnDestroy {
  protected readonly loading = signal<boolean>(true);
  protected readonly appId = signal<string>('');
  protected readonly appName = signal<string>('');
  protected readonly appHasLogo = signal<boolean>(false);
  protected readonly logoLoadError = signal<boolean>(false);
  protected readonly versionId = signal<string>('');

  // Form fields
  protected readonly methodName = signal<string>('');
  protected readonly methodVersion = signal<string>('');
  protected readonly methodDescription = signal<string>('');
  protected readonly methodTypes = signal<string[]>([]);
  protected readonly imCapabilities = signal<CapabilityGroup[]>([]);
  protected readonly initialCapabilities = signal<CapabilityGroup[]>([]);

  // Tutorial content
  protected readonly methodTutorial = signal<string>('');

  // Tutorial file
  protected readonly tutorialDragOver = signal<boolean>(false);
  protected readonly tutorialFiles = signal<{ name: string; file?: File; isNew: boolean }[]>([]);
  protected readonly hadInitialFile = signal<boolean>(false);
  protected readonly fileWarning = signal<boolean>(false);
  private fileWarningTimer: ReturnType<typeof setTimeout> | null = null;

  // Connector
  protected readonly connector = signal<ImplementationListItem | null>(null);
  protected readonly connectorCapsExpanded = signal<Set<string>>(new Set());

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
    this.appId.set(aId);
    this.versionId.set(vId);

    this.applicationService.getById(aId).subscribe({
      next: (app) => {
        this.appName.set(app.displayName);
        this.appHasLogo.set(hasLogoDetail(app));
        const ver = app.integrationMethods?.find(m => m.id === vId);
        if (ver) {
          this.methodName.set(ver.displayName ?? '');
          this.methodVersion.set(ver.revision ?? '');
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
          if (ver.filePath) {
            const fileName = ver.filePath.split('/').pop() ?? ver.filePath;
            this.tutorialFiles.set([{ name: fileName, isNew: false }]);
            this.hadInitialFile.set(true);
          }
          this.initialCapabilities.set(
            (ver.objectClassCapabilities ?? []).map(oc => ({
              objectClass: oc.objectName,
              capabilityNames: oc.capabilities ?? []
            }))
          );
        }
      }
    });

    this.applicationService.getImplementationsByApplicationId(aId).subscribe({
      next: (impls) => {
        const impl = impls.find(i => i.id === vId) ?? null;
        this.connector.set(impl);
        this.loading.set(false);
        setTimeout(() => this.initEditor(), 50);
      },
      error: () => {
        this.loading.set(false);
        setTimeout(() => this.initEditor(), 50);
      }
    });
  }

  ngOnDestroy(): void {
    if (this.easyMde) {
      this.easyMde.toTextArea();
      this.easyMde = null;
    }
    if (this.fileWarningTimer) clearTimeout(this.fileWarningTimer);
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
    if (this.tutorialFiles().length > 0) {
      this.showFileWarning();
      return;
    }
    const f = files[0];
    if (f) this.tutorialFiles.set([{ name: f.name, file: f, isNew: true }]);
  }

  private showFileWarning(): void {
    if (this.fileWarningTimer) clearTimeout(this.fileWarningTimer);
    this.fileWarning.set(true);
    this.fileWarningTimer = setTimeout(() => this.fileWarning.set(false), 4000);
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

  protected save(): void {
    this.doSave(false);
  }

  protected saveAsNewVersion(): void {
    this.doSave(true);
  }

  private doSave(minorBump: boolean): void {
    const tutorial = this.easyMde ? this.easyMde.value() : this.methodTutorial();
    const capabilities = this.imCapabilities().length > 0
      ? this.imCapabilities()
      : this.initialCapabilities();

    const newFile = this.tutorialFiles().find(f => f.isNew && f.file)?.file ?? null;
    // removeFile: clear old path only when user explicitly removed the file without uploading a replacement
    const removeFile = this.hadInitialFile() && this.tutorialFiles().length === 0;

    this.applicationService.editIntegrationMethod(
      this.appId(),
      this.versionId(),
      this.methodVersion(),
      {
        displayName: this.methodName(),
        description: this.methodDescription(),
        tutorial,
        capabilities: capabilities.map(g => ({ objectClass: g.objectClass, capabilityNames: g.capabilityNames })),
        removeFile,
        minorBump
      }
    ).subscribe({
      next: (newRevision) => {
        this.methodVersion.set(newRevision);
        if (newFile) {
          this.applicationService.uploadTutorialFile(this.appId(), this.versionId(), newRevision, newFile)
            .subscribe({
              next: () => this.router.navigate(['/applications', this.appId()]),
              error: (err) => {
                console.error('Tutorial file upload failed', err);
                this.router.navigate(['/applications', this.appId()]);
              }
            });
        } else {
          this.router.navigate(['/applications', this.appId()]);
        }
      },
      error: (err) => console.error('Save failed', err)
    });
  }
}
