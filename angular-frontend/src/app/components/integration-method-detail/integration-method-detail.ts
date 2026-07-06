/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, OnInit, OnDestroy, signal, computed } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import EasyMDE from 'easymde';
import { ApplicationService } from '../../services/application.service';
import { AuthService } from '../../services/auth.service';
import { PageHeader } from '../page-header/page-header';
import { ImplementationListItem } from '../../models/implementation-list-item.model';
import { hasLogoDetail, MidpointVersion, ObjectClassCapability } from '../../models/application-detail.model';

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
  protected readonly specificCapabilities = signal<ObjectClassCapability[]>([]);
  protected readonly methodTutorial = signal<string>('');
  protected readonly tutorialFiles = signal<string[]>([]);

  // Supported midPoint version range
  protected readonly midpointVersions = signal<MidpointVersion[]>([]);
  protected readonly methodMinVersionId = signal<number | null>(null);
  protected readonly methodMaxVersionId = signal<number | null>(null);
  protected readonly midpointMinVersion = computed(() => this.resolveMidpointVersion(this.methodMinVersionId()));
  protected readonly midpointMaxVersion = computed(() => this.resolveMidpointVersion(this.methodMaxVersionId()));

  // Connectors
  protected readonly connectors = signal<ImplementationListItem[]>([]);
  protected readonly expandedCaps = signal<Set<string>>(new Set());

  // Bundle download warning toast (stays until dismissed)
  protected readonly bundleWarning = signal<string | null>(null);

  private easyMde: EasyMDE | null = null;

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
          this.methodDescription.set(ver.description ?? '');
          this.methodTypes.set(ver.integMethodTypes ?? []);
          this.methodTutorial.set(ver.tutorial ?? '');
          this.methodMinVersionId.set(ver.midpointMinVersionId);
          this.methodMaxVersionId.set(ver.midpointMaxVersionId);
          this.setCapabilities(ver.objectClassCapabilities);
          this.loadTutorialFiles(aId, vId, ver.revision ?? '');
          this.loadConnectors(aId, vId, ver.revision ?? '');
        } else {
          this.finishLoading();
        }
      },
      error: () => this.finishLoading()
    });
  }

  // Global capabilities are stored under the reserved 'Global' object class; the rest are
  // grouped per object class so we can show their globality separately.
  private setCapabilities(occs: ObjectClassCapability[] | null): void {
    const groups = occs ?? [];
    this.globalCapabilities.set(
      groups.filter(o => o.objectName === 'Global').flatMap(o => o.capabilities ?? [])
    );
    const specifics = groups.filter(o => o.objectName !== 'Global' && (o.capabilities?.length ?? 0) > 0);
    this.specificCapabilities.set(specifics);
    // Start with Global expanded and the specific groups collapsed; each can then be toggled independently.
    const expanded = new Set<string>();
    if (this.globalCapabilities().length > 0) expanded.add('global');
    this.expandedCaps.set(expanded);
  }

  private resolveMidpointVersion(id: number | null): string {
    if (id === null) return '';
    return this.midpointVersions().find(v => v.id === id)?.version ?? '';
  }

  private loadTutorialFiles(appId: string, methodId: string, revision: string): void {
    this.applicationService.listTutorialFiles(appId, methodId, revision).subscribe({
      next: (names) => this.tutorialFiles.set(names),
      error: () => this.tutorialFiles.set([])
    });
  }

  protected tutorialFileUrl(name: string): string {
    return this.applicationService.getTutorialFileUrl(this.appId(), this.versionId(), this.methodVersion(), name);
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

  // Connector detail sub-sections (Repository, Framework, Source, Implementation).
  // Tracks the COLLAPSED ones so every section starts expanded (matches the design).
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

  /** A connector's object-class capabilities, with the Global class first when present. */
  protected orderedConnectorCaps(caps: ObjectClassCapability[] | null | undefined): ObjectClassCapability[] {
    return [...(caps ?? [])].sort((a, b) =>
      (a.objectName === 'Global' ? 0 : 1) - (b.objectName === 'Global' ? 0 : 1)
    );
  }

  /** Derive the version badge ("v1", "v2", …) from the major part of the revision. */
  protected versionBadge(revision: string | null): string {
    const major = parseInt((revision ?? '').split('.')[0], 10);
    return isNaN(major) ? 'v1' : `v${major}`;
  }

  /** Turn a build-framework enum (e.g. "MAVEN") into a friendly label ("Maven"). */
  protected formatBuildTool(value: string): string {
    if (!value) return '—';
    return value.charAt(0).toUpperCase() + value.slice(1).toLowerCase();
  }

  /** Friendly labels for the LicenseType enum (mirrors the publish form). */
  private readonly licenseLabels: Record<string, string> = {
    MIT: 'MIT',
    APACHE_2: 'Apache 2.0',
    BSD: 'BSD',
    EUPL: 'EUPL 1.2',
  };

  protected formatLicense(value: string): string {
    if (!value) return '—';
    return this.licenseLabels[value] ?? value;
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
    this.router.navigate(['/applications', this.appId(), 'integration-method', this.versionId(), this.methodVersion(), 'edit']);
  }

  protected downloadConnector(): void {
    this.applicationService.downloadBundle(this.appId(), this.versionId(), this.methodVersion()).subscribe({
      next: (warning) => this.bundleWarning.set(warning),
      error: () => this.bundleWarning.set('Failed to download the bundle. Please try again.')
    });
  }

  protected closeBundleWarning(): void {
    this.bundleWarning.set(null);
  }
}
