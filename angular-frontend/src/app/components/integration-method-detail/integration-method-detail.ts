/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, OnInit, OnDestroy, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import EasyMDE from 'easymde';
import { ApplicationService } from '../../services/application.service';
import { PageHeader } from '../page-header/page-header';
import { ImplementationListItem } from '../../models/implementation-list-item.model';
import { hasLogoDetail } from '../../models/application-detail.model';

@Component({
  selector: 'app-integration-method-detail',
  standalone: true,
  imports: [CommonModule, PageHeader],
  templateUrl: './integration-method-detail.html',
  styleUrls: ['./integration-method-detail.scss']
})
export class IntegrationMethodDetail implements OnInit, OnDestroy {
  protected readonly loading = signal<boolean>(true);
  protected readonly appId = signal<string>('');
  protected readonly appName = signal<string>('');
  protected readonly appHasLogo = signal<boolean>(false);
  protected readonly logoLoadError = signal<boolean>(false);
  protected readonly versionId = signal<string>('');

  // Read-only method fields
  protected readonly methodName = signal<string>('');
  protected readonly methodVersion = signal<string>('');
  protected readonly methodDescription = signal<string>('');
  protected readonly methodTypes = signal<string[]>([]);
  protected readonly globalCapabilities = signal<string[]>([]);
  protected readonly methodTutorial = signal<string>('');
  protected readonly tutorialFileName = signal<string | null>(null);

  // Connectors
  protected readonly connectors = signal<ImplementationListItem[]>([]);
  protected readonly expandedConnectors = signal<Set<number>>(new Set());
  protected readonly expandedCaps = signal<Set<string>>(new Set());

  private easyMde: EasyMDE | null = null;

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
          this.globalCapabilities.set(this.flattenCapabilities(ver.objectClassCapabilities));
          if (ver.filePath) {
            this.tutorialFileName.set(ver.filePath.split('/').pop() ?? ver.filePath);
          }
          this.loadConnectors(aId, vId, ver.revision ?? '');
        } else {
          this.finishLoading();
        }
      },
      error: () => this.finishLoading()
    });
  }

  private flattenCapabilities(occs: { objectName: string; capabilities: string[] }[] | null): string[] {
    if (!occs) return [];
    const seen = new Set<string>();
    for (const occ of occs) {
      for (const cap of occ.capabilities ?? []) seen.add(cap);
    }
    return Array.from(seen);
  }

  private loadConnectors(appId: string, methodId: string, revision: string): void {
    this.applicationService.getConnectorsForIntegrationMethod(appId, methodId, revision).subscribe({
      next: (connectors) => {
        this.connectors.set(connectors);
        // First connector expanded by default
        if (connectors.length > 0) this.expandedConnectors.set(new Set([0]));
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
    const el = document.getElementById('view-tutorial-editor') as HTMLTextAreaElement | null;
    if (!el || this.easyMde) return;
    this.easyMde = new EasyMDE({
      element: el,
      spellChecker: false,
      autosave: { enabled: false, uniqueId: 'view-tutorial-' + this.versionId() },
      toolbar: ['bold', 'italic', 'strikethrough', '|',
                'heading-1', 'heading-2', '|',
                'unordered-list', 'ordered-list', '|',
                'link', '|', 'preview', 'side-by-side'],
      placeholder: 'No tutorial provided.',
    });
    this.easyMde.value(this.methodTutorial());
    // Lock content: read-only, preview by default
    this.easyMde.codemirror.setOption('readOnly', 'nocursor');
    EasyMDE.togglePreview(this.easyMde);
  }

  protected toggleConnector(index: number): void {
    this.expandedConnectors.update(s => {
      const ns = new Set(s);
      ns.has(index) ? ns.delete(index) : ns.add(index);
      return ns;
    });
  }

  protected isConnectorExpanded(index: number): boolean {
    return this.expandedConnectors().has(index);
  }

  protected toggleCaps(key: string): void {
    this.expandedCaps.update(s => {
      const ns = new Set(s);
      ns.has(key) ? ns.delete(key) : ns.add(key);
      return ns;
    });
  }

  protected isCapsExpanded(key: string): boolean {
    return this.expandedCaps().has(key);
  }

  protected formatCapabilityText(text: string): string {
    if (!text) return '';
    const withSpaces = text.replace(/_/g, ' ').toLowerCase();
    return withSpaces.charAt(0).toUpperCase() + withSpaces.slice(1);
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

  protected goBack(): void {
    this.router.navigate(['/applications', this.appId()]);
  }

  protected editAndUpgrade(): void {
    this.router.navigate(['/applications', this.appId(), 'integration-method', this.versionId(), 'edit']);
  }

  protected downloadConnector(): void {
    this.applicationService.downloadConnector(this.versionId());
  }
}
