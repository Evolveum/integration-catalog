/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, OnInit, signal, computed } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import Asciidoctor from 'asciidoctor';
import { ApplicationService } from '../../services/application.service';
import { AuthService, UserRole } from '../../services/auth.service';
import { PageHeader } from '../page-header/page-header';
import { ApprovalConfirmModal } from '../approval-confirm-modal/approval-confirm-modal';
import { ImplementationListItem } from '../../models/implementation-list-item.model';
import { hasLogoDetail, MidpointVersion, ObjectClassCapability } from '../../models/application-detail.model';

// Single Asciidoctor engine instance shared by the component; the tutorial is
// authored in AsciiDoc and rendered read-only here.
const asciidoctor = Asciidoctor();

@Component({
  selector: 'app-integration-method-detail',
  standalone: true,
  imports: [CommonModule, PageHeader, ApprovalConfirmModal],
  templateUrl: './integration-method-detail.html',
  styleUrls: ['./integration-method-detail.scss']
})
export class IntegrationMethodDetail implements OnInit {
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

  // Lifecycle state of the opened revision; the footer actions (Edit and upgrade, Download)
  // are only offered for published (ACTIVE) methods.
  protected readonly methodLifecycleState = signal<string | null>(null);
  protected readonly isActive = computed(() => this.methodLifecycleState() === 'ACTIVE');
  // An in-review ("Awaiting approval") method offers Approve/Reject to superusers.
  protected readonly isInReview = computed(() => this.methodLifecycleState() === 'IN_REVIEW');
  protected readonly isProcessingApproval = signal<boolean>(false);
  protected readonly approvalError = signal<string>('');

  // Which confirmation modal is open (null = none); the modal component owns the two-step flow.
  protected readonly confirmMode = signal<'approve' | 'reject' | null>(null);
  protected readonly methodAuthor = signal<string>('');
  private readonly submittedDate = 'May 20, 2026';

  protected readonly confirmConnectorName = computed(() => {
    const c = this.connectors()[0];
    return c?.bundleDisplayName || c?.name || this.methodName() || '—';
  });
  protected readonly submittedByLabel = computed(() =>
    `${this.methodAuthor() || '—'} · ${this.submittedDate}`
  );

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

  // Tutorial (AsciiDoc source) rendered to embeddable HTML for read-only display.
  // Angular sanitizes the bound HTML; Asciidoctor's default 'secure' mode also
  // disables includes and scripts.
  protected readonly tutorialHtml = computed(() => {
    const content = this.methodTutorial().trim();
    return content ? String(asciidoctor.convert(content)) : '';
  });

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
          this.methodAuthor.set(ver.author ?? '');
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

  // ── "Connector compatibility" modal (read-only view of the connector version range) ──
  protected readonly compatConnector = signal<ImplementationListItem | null>(null);

  protected openCompatibility(connector: ImplementationListItem): void {
    this.compatConnector.set(connector);
  }

  protected closeCompatibility(): void {
    this.compatConnector.set(null);
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

  /** Only superusers may approve/reject an in-review revision. */
  protected isSuperuser(): boolean {
    return this.authService.currentRole() === UserRole.Superuser;
  }

  // ── Approve/Reject confirmation modal ─────────────────────────────────────
  protected openApproveConfirm(): void {
    this.openConfirm('approve');
  }

  protected openRejectConfirm(): void {
    this.openConfirm('reject');
  }

  private openConfirm(mode: 'approve' | 'reject'): void {
    this.approvalError.set('');
    this.confirmMode.set(mode);
  }

  protected closeConfirm(): void {
    if (this.isProcessingApproval()) return;
    this.confirmMode.set(null);
  }

  protected submitConfirm(): void {
    if (this.confirmMode() === 'approve') {
      this.approve();
    } else if (this.confirmMode() === 'reject') {
      this.reject();
    }
  }

  protected approve(): void {
    if (this.isProcessingApproval()) return;
    this.approvalError.set('');
    this.isProcessingApproval.set(true);
    this.applicationService.publishIntegrationMethod(this.appId(), this.versionId(), this.methodVersion()).subscribe({
      next: () => this.goBack(),
      error: (err) => this.handleApprovalError(err)
    });
  }

  protected reject(): void {
    if (this.isProcessingApproval()) return;
    this.approvalError.set('');
    this.isProcessingApproval.set(true);
    this.applicationService.rejectIntegrationMethod(this.appId(), this.versionId(), this.methodVersion()).subscribe({
      next: () => this.goBack(),
      error: (err) => this.handleApprovalError(err)
    });
  }

  private handleApprovalError(err: unknown): void {
    console.error('Approval action failed', err);
    this.isProcessingApproval.set(false);
    const e = err as { error?: { message?: string } | string; message?: string };
    const message = (typeof e?.error === 'object' ? e.error?.message : e?.error) || e?.message;
    this.approvalError.set(message || 'The action failed. Please try again.');
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
