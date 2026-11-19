/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, computed, effect, inject, linkedSignal, signal, ViewChild } from '@angular/core';
import { CommonModule, DatePipe, Location } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { AuthService, UserRole } from '../../services/auth.service';
import { ApplicationService, ConnectorWithoutDownload } from '../../services/application.service';
import { ToastService } from '../../services/toast.service';
import { PageHeader } from '../page-header/page-header';
import { Pager } from '../pager/pager';
import { FilterMenu } from '../filter-menu/filter-menu';
import { ApprovalConfirmModal } from '../approval-confirm-modal/approval-confirm-modal';
import { StartReviewModal } from '../start-review-modal/start-review-modal';
import { ManualFillModal } from '../manual-fill-modal/manual-fill-modal';
import { MyIntegrationMethod } from '../../models/my-items.model';
import { maintainerFilterOptions, maintainerKey } from '../../models/maintainer.model';
import { versionBadge } from '../../core/version-badge';
import { NewTabRouteDirective } from '../../directives/new-tab-route.directive';

/** From this many days in the queue the waiting time turns red. */
const OVERDUE_DAYS = 3;

/**
 * The superuser's approval queue: every revision awaiting approval or under review across the
 * catalog, with the review actions of the application detail page.
 */
@Component({
  selector: 'app-approval-queue-page',
  standalone: true,
  imports: [CommonModule, RouterLink, PageHeader, ApprovalConfirmModal, StartReviewModal, ManualFillModal, Pager, FilterMenu,
    NewTabRouteDirective],
  templateUrl: './approval-queue-page.html',
  styleUrls: ['./approval-queue-page.scss'],
  host: { '(document:keydown.escape)': 'closeMenu()' }
})
export class ApprovalQueuePage {
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly applicationService = inject(ApplicationService);
  private readonly toastService = inject(ToastService);
  protected readonly authService = inject(AuthService);
  protected readonly versionBadge = versionBadge;
  private readonly datePipe = new DatePipe('en-US');

  @ViewChild(ApprovalConfirmModal) approvalConfirmModal?: ApprovalConfirmModal;

  protected readonly revisions = signal<MyIntegrationMethod[] | null>(null);
  protected readonly loading = signal(false);
  protected readonly loadFailed = signal(false);

  // Multi-select like the homepage chips: an empty set lets everything through.
  protected readonly states = signal<ReadonlySet<string>>(new Set());
  protected readonly applications = signal<ReadonlySet<string>>(new Set());
  /** Keys of the maintainers to show. */
  protected readonly maintainers = signal<ReadonlySet<string>>(new Set());
  protected readonly search = signal('');

  protected readonly openMenuKey = signal<string | null>(null);

  protected readonly stateOptions = [
    { value: 'IN_REVIEW', label: 'Awaiting approval' },
    { value: 'REVIEWING', label: 'Under review' }
  ];

  protected readonly isSuperuser = computed(() => this.authService.currentRole() === UserRole.Superuser);

  protected readonly rows = computed(() => {
    const states = this.states();
    const applications = this.applications();
    const maintainers = this.maintainers();
    const query = this.search().trim().toLowerCase();
    return (this.revisions() ?? [])
      .filter(r => !states.size || states.has(r.lifecycleState ?? ''))
      .filter(r => !applications.size || applications.has(r.applicationId))
      .filter(r => !maintainers.size || maintainers.has(maintainerKey(r.maintainer)))
      .filter(r => !query
        || (r.displayName ?? '').toLowerCase().includes(query)
        || (r.applicationDisplayName ?? '').toLowerCase().includes(query));
  });

  protected readonly pageSize = 15;
  /** Back to the first page whenever a filter changes; a reload after an action keeps the page. */
  private readonly currentPage = linkedSignal({
    source: computed(() => [this.states(), this.applications(), this.maintainers(), this.search()]),
    computation: () => 0
  });
  protected readonly totalPages = computed(() => Math.ceil(this.rows().length / this.pageSize));
  /** The current page, kept in range when a reload leaves fewer pages. */
  protected readonly page = computed(() => Math.min(this.currentPage(), Math.max(this.totalPages() - 1, 0)));
  protected readonly pagedRows = computed(() =>
    this.rows().slice(this.page() * this.pageSize, (this.page() + 1) * this.pageSize));

  protected goToPage(page: number): void {
    this.currentPage.set(page);
    window.scrollTo({ top: 0 });
  }

  protected readonly applicationOptions = computed(() => {
    const seen = new Map<string, string>();
    for (const r of this.revisions() ?? []) seen.set(r.applicationId, r.applicationDisplayName ?? '');
    return [...seen.entries()]
      .map(([value, label]) => ({ value, label }))
      .sort((a, b) => a.label.localeCompare(b.label));
  });

  protected readonly maintainerOptions = computed(() =>
    maintainerFilterOptions((this.revisions() ?? []).map(r => r.maintainer)));

  protected readonly totalCount = computed(() => (this.revisions() ?? []).length);
  protected readonly awaitingCount = computed(() => this.countState('IN_REVIEW'));
  protected readonly reviewingCount = computed(() => this.countState('REVIEWING'));

  protected readonly hasFilters = computed(() =>
    this.states().size > 0 || this.applications().size > 0
    || this.maintainers().size > 0 || !!this.search().trim());

  constructor() {
    // The profile loads asynchronously at startup, so a page opened directly waits for it.
    effect(() => {
      if (this.isSuperuser() && !this.revisions() && !this.loading() && !this.loadFailed()) {
        this.load();
      }
    });
  }

  protected load(): void {
    this.loading.set(true);
    this.loadFailed.set(false);
    this.applicationService.getReviewQueue().subscribe({
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

  // ==================== Filters ====================

  /** Behind the stat cards' "View items": shows just that slice, every other filter cleared. */
  protected viewItems(state: string | null): void {
    this.resetFilters();
    if (state) this.states.set(new Set([state]));
  }

  protected isOnlyState(state: string): boolean {
    return this.states().size === 1 && this.states().has(state);
  }

  protected resetFilters(): void {
    this.states.set(new Set());
    this.applications.set(new Set());
    this.maintainers.set(new Set());
    this.search.set('');
  }

  protected toggleState(value: string): void {
    this.states.update(set => toggled(set, value));
  }

  protected toggleApplication(id: string): void {
    this.applications.update(set => toggled(set, id));
  }

  protected toggleMaintainer(key: string): void {
    this.maintainers.update(set => toggled(set, key));
  }

  protected clearFilter(filter: 'states' | 'applications' | 'maintainers'): void {
    this[filter].set(new Set());
  }

  // ==================== Rows ====================

  protected initials(name: string | null): string {
    const parts = (name ?? '').split('@')[0].split(/[\s._-]+/).filter(Boolean);
    if (parts.length === 0) return '?';
    const tail = parts.length > 1 ? parts[parts.length - 1].charAt(0) : '';
    return (parts[0].charAt(0) + tail).toUpperCase();
  }

  /**
   * How long the revision has waited, counted from its last change: the submission while it
   * awaits approval, the review start once it is under review.
   */
  protected inQueue(revision: MyIntegrationMethod): string {
    const hours = this.hoursWaiting(revision);
    if (hours === null) return '—';
    if (hours < 1) return '< 1 h';
    if (hours < 24) return `${Math.floor(hours)} h`;
    const days = Math.floor(hours / 24);
    return `${days} ${days === 1 ? 'day' : 'days'}`;
  }

  protected isOverdue(revision: MyIntegrationMethod): boolean {
    const hours = this.hoursWaiting(revision);
    return hours !== null && hours >= OVERDUE_DAYS * 24;
  }

  private hoursWaiting(revision: MyIntegrationMethod): number | null {
    if (!revision.updatedAt) return null;
    return Math.max(0, (Date.now() - new Date(revision.updatedAt).getTime()) / 3_600_000);
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

  protected detailsRoute(revision: MyIntegrationMethod): string[] {
    return ['/applications', revision.applicationId, 'integration-method', revision.id, revision.revision, 'details'];
  }

  protected openDetails(revision: MyIntegrationMethod): void {
    this.closeMenu();
    this.router.navigate(this.detailsRoute(revision));
  }

  /** Returns where the user came from, or to the catalog when opened directly. */
  protected goBack(): void {
    if (window.history.length > 1) {
      this.location.back();
    } else {
      this.router.navigate(['/applications']);
    }
  }

  // ==================== Approve ====================

  protected readonly confirmRevision = signal<MyIntegrationMethod | null>(null);
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

  /** Opens the panel that checks the build data and the support ticket before approving. */
  protected openApprove(revision: MyIntegrationMethod): void {
    this.closeMenu();
    this.approvalError.set('');
    this.confirmRevision.set(revision);
  }

  protected closeApprove(): void {
    if (this.isProcessingApproval()) return;
    this.confirmRevision.set(null);
  }

  protected submitApprove(): void {
    const revision = this.confirmRevision();
    if (!revision || this.isProcessingApproval()) return;
    this.approvalError.set('');
    this.isProcessingApproval.set(true);
    this.applicationService.publishIntegrationMethod(revision.id, revision.revision).subscribe({
      next: () => {
        this.isProcessingApproval.set(false);
        this.confirmRevision.set(null);
        this.load();
      },
      error: err => {
        this.isProcessingApproval.set(false);
        this.approvalError.set(errorMessage(err));
      }
    });
  }

  /** How rejecting from the queue works is still to be defined. */
  protected reject(): void {
    this.closeMenu();
    this.toastService.show('Reject', 'Rejecting from the approval queue is not available yet.', 'info');
  }

  // ==================== Manual fill (from the approve panel) ====================

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
