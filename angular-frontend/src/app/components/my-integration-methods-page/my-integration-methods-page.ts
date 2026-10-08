/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, computed, effect, inject, linkedSignal, signal, ViewChild } from '@angular/core';
import { CommonModule, DatePipe, Location } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService, UserRole } from '../../services/auth.service';
import { ApplicationService, ConnectorWithoutDownload } from '../../services/application.service';
import { ToastService } from '../../services/toast.service';
import { PageHeader } from '../page-header/page-header';
import { Pager } from '../pager/pager';
import { FilterMenu } from '../filter-menu/filter-menu';
import { ApprovalConfirmModal } from '../approval-confirm-modal/approval-confirm-modal';
import { StartReviewModal } from '../start-review-modal/start-review-modal';
import { ManualFillModal } from '../manual-fill-modal/manual-fill-modal';
import { DownloadInfoModal } from '../download-info-modal/download-info-modal';
import { MyIntegrationMethod } from '../../models/my-items.model';
import {
  MAINTAINER_CATEGORY_LABELS, MaintainerCategory, maintainerFilterOptions, maintainerKey, maintainerLabel
} from '../../models/maintainer.model';
import { versionBadge } from '../../core/version-badge';

/** All revisions of one method, newest first; `revisions` holds only those the filters let through. */
interface MethodGroup {
  id: string;
  applicationId: string;
  applicationDisplayName: string | null;
  applicationHasLogo: boolean;
  displayName: string | null;
  maintainerKey: string;
  maintainerLabel: string;
  maintainerCategory: MaintainerCategory | null;
  latest: MyIntegrationMethod;
  versionCount: number;
  revisions: MyIntegrationMethod[];
}

/**
 * Self-service list of the integration methods the user maintains, personally or through their
 * organization, with the same per-revision actions the application detail page offers. With the
 * route's `scope: 'all'` it lists every method in the catalog instead, for a superuser.
 */
@Component({
  selector: 'app-my-integration-methods-page',
  standalone: true,
  imports: [CommonModule, RouterLink, PageHeader, ApprovalConfirmModal, StartReviewModal, ManualFillModal,
    DownloadInfoModal, Pager, FilterMenu],
  templateUrl: './my-integration-methods-page.html',
  styleUrls: ['./my-integration-methods-page.scss'],
  host: { '(document:keydown.escape)': 'closeMenu()' }
})
export class MyIntegrationMethodsPage {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly location = inject(Location);
  private readonly applicationService = inject(ApplicationService);
  private readonly toastService = inject(ToastService);
  protected readonly authService = inject(AuthService);
  protected readonly versionBadge = versionBadge;
  private readonly datePipe = new DatePipe('en-US');

  @ViewChild(ApprovalConfirmModal) approvalConfirmModal?: ApprovalConfirmModal;

  /** Every method in the catalog (superuser) instead of the user's own. */
  protected readonly allMethods = this.route.snapshot.data['scope'] === 'all';

  protected readonly revisions = signal<MyIntegrationMethod[] | null>(null);
  protected readonly loading = signal(false);
  protected readonly loadFailed = signal(false);

  // Multi-select like the homepage chips: an empty set lets everything through.
  protected readonly states = signal<ReadonlySet<string>>(new Set());
  protected readonly applications = signal<ReadonlySet<string>>(new Set());
  protected readonly authors = signal<ReadonlySet<string>>(new Set());
  /** Keys of the maintainers to show (all-methods page only). */
  protected readonly maintainers = signal<ReadonlySet<string>>(new Set());
  /** Set by the Organization / Evolveum / Community stat cards. */
  protected readonly maintainerCategory = signal<MaintainerCategory | null>(null);
  protected readonly search = signal('');

  protected readonly collapsedGroups = signal<ReadonlySet<string>>(new Set());
  /** `filter:<name>`, `group:<id>` or `row:<id>:<revision>` of the open dropdown. */
  protected readonly openMenuKey = signal<string | null>(null);

  protected readonly stateOptions: { value: string; label: string }[] = [
    { value: 'IN_REVIEW', label: 'Awaiting approval' },
    { value: 'REVIEWING', label: 'Under review' },
    { value: 'ACTIVE', label: 'Published' },
    { value: 'REJECTED', label: 'Rejected' }
  ];

  protected readonly isSuperuser = computed(() => this.authService.currentRole() === UserRole.Superuser);

  /** Start/stop review and approve/reject: on the all-methods page only, the approval queue owns them otherwise. */
  protected readonly showReviewActions = this.allMethods;

  /** Every method with all its revisions, before any filter. */
  private readonly allGroups = computed<MethodGroup[]>(() => {
    const groups = new Map<string, MethodGroup>();
    for (const revision of this.revisions() ?? []) {
      const group = groups.get(revision.id);
      if (group) {
        group.revisions.push(revision);
        group.versionCount++;
      } else {
        groups.set(revision.id, {
          id: revision.id,
          applicationId: revision.applicationId,
          applicationDisplayName: revision.applicationDisplayName,
          applicationHasLogo: revision.applicationHasLogo,
          displayName: revision.displayName,
          maintainerKey: maintainerKey(revision.maintainer),
          maintainerLabel: maintainerLabel(revision.maintainer),
          maintainerCategory: revision.maintainer?.category ?? null,
          latest: revision,
          versionCount: 1,
          revisions: [revision]
        });
      }
    }
    return [...groups.values()];
  });

  protected readonly groups = computed<MethodGroup[]>(() => {
    const states = this.states();
    const authors = this.authors();
    const applications = this.applications();
    const maintainers = this.maintainers();
    const category = this.maintainerCategory();
    const query = this.search().trim().toLowerCase();
    return this.allGroups()
      .filter(g => !applications.size || applications.has(g.applicationId))
      .filter(g => !maintainers.size || maintainers.has(g.maintainerKey))
      .filter(g => !category || g.maintainerCategory === category)
      .filter(g => !query
        || (g.displayName ?? '').toLowerCase().includes(query)
        || (g.applicationDisplayName ?? '').toLowerCase().includes(query))
      .map(g => ({
        ...g,
        revisions: g.revisions.filter(r =>
          (!states.size || states.has(r.lifecycleState ?? '')) && (!authors.size || authors.has(r.author ?? '')))
      }))
      .filter(g => g.revisions.length > 0);
  });

  protected readonly pageSize = 10;
  /** Back to the first page whenever a filter changes; a reload after an action keeps the page. */
  private readonly currentPage = linkedSignal({
    source: computed(() => [this.states(), this.applications(), this.authors(), this.maintainers(), this.maintainerCategory(), this.search()]),
    computation: () => 0
  });
  protected readonly totalPages = computed(() => Math.ceil(this.groups().length / this.pageSize));
  /** The current page, kept in range when a reload leaves fewer pages. */
  protected readonly page = computed(() => Math.min(this.currentPage(), Math.max(this.totalPages() - 1, 0)));
  protected readonly pagedGroups = computed(() =>
    this.groups().slice(this.page() * this.pageSize, (this.page() + 1) * this.pageSize));

  protected goToPage(page: number): void {
    this.currentPage.set(page);
    window.scrollTo({ top: 0 });
  }

  protected readonly applicationOptions = computed(() => {
    const seen = new Map<string, string>();
    for (const g of this.allGroups()) seen.set(g.applicationId, g.applicationDisplayName ?? '');
    return [...seen.entries()]
      .map(([value, label]) => ({ value, label }))
      .sort((a, b) => a.label.localeCompare(b.label));
  });

  protected readonly authorOptions = computed(() =>
    [...new Set((this.revisions() ?? []).map(r => r.author).filter((a): a is string => !!a))]
      .sort((a, b) => a.localeCompare(b))
      .map(a => ({ value: a, label: a })));

  protected readonly maintainerOptions = computed(() =>
    maintainerFilterOptions((this.revisions() ?? []).map(r => r.maintainer)));

  protected readonly totalCount = computed(() => this.allGroups().length);
  protected readonly organizationCount = computed(() => this.countCategory('ORG'));
  protected readonly evolveumCount = computed(() => this.countCategory('EVOLVEUM'));
  protected readonly communityCount = computed(() => this.countCategory('COMMUNITY'));
  protected readonly awaitingCount = computed(() => this.countState('IN_REVIEW'));
  protected readonly reviewingCount = computed(() => this.countState('REVIEWING'));
  protected readonly publishedCount = computed(() => this.countState('ACTIVE'));
  protected readonly rejectedCount = computed(() => this.countState('REJECTED'));

  protected readonly hasFilters = computed(() =>
    this.states().size > 0 || !!this.maintainerCategory() || this.maintainers().size > 0
    || this.applications().size > 0 || this.authors().size > 0 || !!this.search().trim());

  constructor() {
    // The profile loads asynchronously at startup, so a page opened directly waits for it.
    effect(() => {
      if (this.authService.profile() && (!this.allMethods || this.isSuperuser())
          && !this.revisions() && !this.loading() && !this.loadFailed()) {
        this.load();
      }
    });
  }

  protected load(): void {
    this.loading.set(true);
    this.loadFailed.set(false);
    const revisions$ = this.allMethods
        ? this.applicationService.getAllIntegrationMethods()
        : this.applicationService.getMyIntegrationMethods();
    revisions$.subscribe({
      next: revisions => {
        this.revisions.set(revisions);
        this.loading.set(false);
      },
      error: () => {
        this.loadFailed.set(true);
        this.loading.set(false);
      }
    });
  }

  private countState(state: string): number {
    return (this.revisions() ?? []).filter(r => r.lifecycleState === state).length;
  }

  private countCategory(category: MaintainerCategory): number {
    return this.allGroups().filter(g => g.maintainerCategory === category).length;
  }

  protected categoryLabel(category: MaintainerCategory): string {
    return MAINTAINER_CATEGORY_LABELS[category];
  }

  // ==================== Filters ====================

  /** Behind the stat cards' "View items": shows just that slice, every other filter cleared. */
  protected viewItems(states: string[], category: MaintainerCategory | null = null): void {
    this.resetFilters();
    this.states.set(new Set(states));
    // Where the Maintainer dropdown exists the card selects its maintainers there; the own-items
    // page has no such dropdown, so the Organization card keeps the category chip.
    if (category && this.allMethods) this.maintainers.set(this.categoryMaintainerKeys(category));
    else this.maintainerCategory.set(category);
  }

  /** Keys of the maintainers of this category among the loaded items. */
  private categoryMaintainerKeys(category: MaintainerCategory): Set<string> {
    return new Set(this.allGroups().filter(g => g.maintainerCategory === category).map(g => g.maintainerKey));
  }

  /** Whether the maintainer filter is exactly this category's maintainers, as its stat card sets it. */
  protected isOnlyCategory(category: MaintainerCategory): boolean {
    const keys = this.categoryMaintainerKeys(category);
    return keys.size > 0 && this.maintainers().size === keys.size && [...keys].every(key => this.maintainers().has(key));
  }

  /** Whether the state filter is exactly these states, as their stat card sets it. */
  protected isOnlyStates(...states: string[]): boolean {
    return this.states().size === states.length && states.every(state => this.states().has(state));
  }

  protected resetFilters(): void {
    this.states.set(new Set());
    this.applications.set(new Set());
    this.authors.set(new Set());
    this.maintainers.set(new Set());
    this.maintainerCategory.set(null);
    this.search.set('');
  }

  protected toggleState(value: string): void {
    this.states.update(set => toggled(set, value));
  }

  protected toggleApplication(id: string): void {
    this.applications.update(set => toggled(set, id));
  }

  protected toggleAuthor(author: string): void {
    this.authors.update(set => toggled(set, author));
  }

  protected toggleMaintainer(key: string): void {
    this.maintainers.update(set => toggled(set, key));
  }

  protected clearFilter(filter: 'states' | 'applications' | 'authors' | 'maintainers'): void {
    this[filter].set(new Set());
  }

  // ==================== Rows ====================

  protected logoUrl(applicationId: string): string {
    return this.applicationService.getLogoUrl(applicationId);
  }

  protected initials(name: string | null): string {
    const parts = (name ?? '').split('@')[0].split(/[\s._-]+/).filter(Boolean);
    if (parts.length === 0) return '?';
    const tail = parts.length > 1 ? parts[parts.length - 1].charAt(0) : '';
    return (parts[0].charAt(0) + tail).toUpperCase();
  }

  /** The review lock: once a superuser starts reviewing, only a superuser may edit the revision. */
  protected canEdit(revision: MyIntegrationMethod): boolean {
    return revision.lifecycleState !== 'REVIEWING' || this.isSuperuser();
  }

  protected isCollapsed(id: string): boolean {
    return this.collapsedGroups().has(id);
  }

  protected toggleGroup(id: string): void {
    this.collapsedGroups.update(collapsed => {
      const next = new Set(collapsed);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  }

  protected rowKey(revision: MyIntegrationMethod): string {
    return `row:${revision.id}:${revision.revision}`;
  }

  protected toggleMenu(key: string): void {
    this.openMenuKey.update(open => open === key ? null : key);
  }

  protected closeMenu(): void {
    this.openMenuKey.set(null);
  }

  protected openDetails(revision: MyIntegrationMethod): void {
    this.closeMenu();
    this.router.navigate(['/applications', revision.applicationId, 'integration-method', revision.id, revision.revision, 'details']);
  }

  protected openEdit(revision: MyIntegrationMethod): void {
    this.closeMenu();
    this.router.navigate(['/applications', revision.applicationId, 'integration-method', revision.id, revision.revision, 'edit']);
  }

  protected openApplication(applicationId: string): void {
    this.closeMenu();
    this.router.navigate(['/applications', applicationId]);
  }

  /** Starts the publish flow, which itself asks for the application. */
  protected createMethod(): void {
    this.router.navigate(['/approve']);
  }

  /** Returns where the user came from, or to the catalog when opened directly. */
  protected goBack(): void {
    if (window.history.length > 1) {
      this.location.back();
    } else {
      this.router.navigate(['/applications']);
    }
  }

  // ==================== Approve / reject ====================

  protected readonly confirmRevision = signal<MyIntegrationMethod | null>(null);
  protected readonly confirmMode = signal<'approve' | 'reject' | null>(null);
  protected readonly isProcessingApproval = signal(false);
  protected readonly approvalError = signal('');

  protected readonly confirmMethodName = computed(() => {
    const r = this.confirmRevision();
    return r ? (r.displayName || 'Integration method') : '';
  });

  protected readonly confirmSubmittedBy = computed(() => {
    const r = this.confirmRevision();
    if (!r) return '';
    return `${r.author || '—'} · ${this.datePipe.transform(r.createdAt, 'MMMM d, yyyy') || '—'}`;
  });

  protected openConfirm(revision: MyIntegrationMethod, mode: 'approve' | 'reject'): void {
    this.closeMenu();
    this.approvalError.set('');
    this.confirmRevision.set(revision);
    this.confirmMode.set(mode);
  }

  protected closeConfirm(): void {
    if (this.isProcessingApproval()) return;
    this.confirmMode.set(null);
    this.confirmRevision.set(null);
  }

  protected submitConfirm(): void {
    const revision = this.confirmRevision();
    const mode = this.confirmMode();
    if (!revision || !mode || this.isProcessingApproval()) return;
    this.approvalError.set('');
    this.isProcessingApproval.set(true);
    const action$ = mode === 'approve'
      ? this.applicationService.publishIntegrationMethod(revision.id, revision.revision)
      : this.applicationService.rejectIntegrationMethod(revision.id, revision.revision);
    action$.subscribe({
      next: () => {
        this.isProcessingApproval.set(false);
        this.confirmMode.set(null);
        this.confirmRevision.set(null);
        this.load();
      },
      error: err => {
        this.isProcessingApproval.set(false);
        this.approvalError.set(errorMessage(err));
      }
    });
  }

  // ==================== Manual fill (from the approve modal) ====================

  protected readonly manualFillConnector = signal<ConnectorWithoutDownload | null>(null);

  protected openManualFill(connector: ConnectorWithoutDownload): void {
    this.manualFillConnector.set(connector);
  }

  protected closeManualFill(): void {
    this.manualFillConnector.set(null);
  }

  protected onManualFillSuccess(): void {
    this.closeManualFill();
    this.approvalConfirmModal?.onManualFillSuccess();
    this.load();
  }

  // ==================== Start / stop review ====================

  protected readonly startReviewRevision = signal<MyIntegrationMethod | null>(null);
  protected readonly isProcessingStartReview = signal(false);
  protected readonly startReviewError = signal('');
  protected readonly isProcessingStopReview = signal(false);

  protected openStartReview(revision: MyIntegrationMethod): void {
    this.closeMenu();
    this.startReviewError.set('');
    this.startReviewRevision.set(revision);
  }

  protected closeStartReview(): void {
    if (this.isProcessingStartReview()) return;
    this.startReviewRevision.set(null);
  }

  protected submitStartReview(): void {
    const revision = this.startReviewRevision();
    if (!revision || this.isProcessingStartReview()) return;
    this.startReviewError.set('');
    this.isProcessingStartReview.set(true);
    this.applicationService.startReviewIntegrationMethod(revision.id, revision.revision).subscribe({
      next: () => {
        this.isProcessingStartReview.set(false);
        this.startReviewRevision.set(null);
        this.load();
      },
      error: err => {
        this.isProcessingStartReview.set(false);
        this.startReviewError.set(errorMessage(err));
      }
    });
  }

  /** Hands the review back: REVIEWING returns to IN_REVIEW, no confirmation, as on the detail page. */
  protected stopReview(revision: MyIntegrationMethod): void {
    this.closeMenu();
    if (this.isProcessingStopReview()) return;
    this.isProcessingStopReview.set(true);
    this.applicationService.stopReviewIntegrationMethod(revision.id, revision.revision).subscribe({
      next: () => {
        this.isProcessingStopReview.set(false);
        this.load();
      },
      error: err => {
        this.isProcessingStopReview.set(false);
        this.toastService.show('Stop review failed', errorMessage(err), 'danger');
      }
    });
  }

  // ==================== Cancel request ====================

  protected readonly cancelRevision = signal<MyIntegrationMethod | null>(null);

  protected openCancel(revision: MyIntegrationMethod): void {
    this.closeMenu();
    this.cancelRevision.set(revision);
  }

  protected closeCancel(): void {
    this.cancelRevision.set(null);
  }

  protected confirmCancel(): void {
    const revision = this.cancelRevision();
    if (!revision) return;
    this.applicationService.cancelIntegrationMethod(revision.id, revision.revision).subscribe({
      next: () => {
        this.closeCancel();
        this.load();
      },
      error: err => {
        this.closeCancel();
        this.toastService.show('Cancel request failed', errorMessage(err), 'danger');
      }
    });
  }

  /** Deleting a published revision has no backend yet. */
  protected deletePublished(): void {
    this.closeMenu();
    this.toastService.show('Delete', 'Deleting a published integration method is not available yet.', 'info');
  }

  // ==================== Download ====================

  protected readonly isDownloadInfoOpen = signal(false);
  protected readonly isDownloadPreparing = signal(false);
  protected readonly downloadInfoFileName = signal('');
  protected readonly downloadInfoFileSize = signal<number | null>(null);

  protected download(revision: MyIntegrationMethod): void {
    this.closeMenu();
    // Open right away: building the bundle can take a while and the click must show a reaction.
    this.downloadInfoFileName.set('');
    this.downloadInfoFileSize.set(null);
    this.isDownloadPreparing.set(true);
    this.isDownloadInfoOpen.set(true);
    this.applicationService.downloadBundle(revision.id, revision.revision).subscribe({
      next: result => {
        if (result.warning) {
          this.toastService.show('Download warning', result.warning, 'warning');
        }
        this.downloadInfoFileName.set(result.fileName);
        this.downloadInfoFileSize.set(result.size);
        this.isDownloadPreparing.set(false);
      },
      error: () => {
        this.isDownloadPreparing.set(false);
        this.isDownloadInfoOpen.set(false);
        this.toastService.show('Download error', 'Failed to download the bundle. Please try again.', 'danger');
      }
    });
  }
}

/** A copy of the set with the value added, or removed when it was there. */
function toggled(set: ReadonlySet<string>, value: string): ReadonlySet<string> {
  const next = new Set(set);
  if (!next.delete(value)) next.add(value);
  return next;
}

/** The server's message for a failed action, else a generic one. */
function errorMessage(err: unknown): string {
  const e = err as { error?: { message?: string } | string; message?: string };
  const message = (typeof e?.error === 'object' ? e.error?.message : e?.error) || e?.message;
  return message || 'The action failed. Please try again.';
}
