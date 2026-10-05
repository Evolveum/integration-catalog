/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, computed, effect, inject, signal, ViewChild } from '@angular/core';
import { CommonModule, DatePipe, Location } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { AuthService, UserRole } from '../../services/auth.service';
import { ApplicationService, ConnectorWithoutDownload } from '../../services/application.service';
import { ToastService } from '../../services/toast.service';
import { PageHeader } from '../page-header/page-header';
import { ApprovalConfirmModal } from '../approval-confirm-modal/approval-confirm-modal';
import { StartReviewModal } from '../start-review-modal/start-review-modal';
import { ManualFillModal } from '../manual-fill-modal/manual-fill-modal';
import { MyIntegrationMethod } from '../../models/my-items.model';
import { maintainerLabel } from '../../models/maintainer.model';
import { versionBadge } from '../../core/version-badge';

/** From this many days in the queue the waiting time turns red. */
const OVERDUE_DAYS = 3;

/**
 * The superuser's approval queue: every revision awaiting approval or under review across the
 * catalog, with the review actions of the application detail page.
 */
@Component({
  selector: 'app-approval-queue-page',
  standalone: true,
  imports: [CommonModule, RouterLink, PageHeader, ApprovalConfirmModal, StartReviewModal, ManualFillModal],
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
  protected readonly authors = signal<ReadonlySet<string>>(new Set());
  protected readonly organizations = signal<ReadonlySet<string>>(new Set());
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
    const authors = this.authors();
    const organizations = this.organizations();
    const query = this.search().trim().toLowerCase();
    return (this.revisions() ?? [])
      .filter(r => !states.size || states.has(r.lifecycleState ?? ''))
      .filter(r => !applications.size || applications.has(r.applicationId))
      .filter(r => !authors.size || authors.has(r.author ?? ''))
      .filter(r => !organizations.size || organizations.has(this.organizationOf(r) ?? ''))
      .filter(r => !query
        || (r.displayName ?? '').toLowerCase().includes(query)
        || (r.applicationDisplayName ?? '').toLowerCase().includes(query));
  });

  protected readonly applicationOptions = computed(() => {
    const seen = new Map<string, string>();
    for (const r of this.revisions() ?? []) seen.set(r.applicationId, r.applicationDisplayName ?? '');
    return [...seen.entries()]
      .map(([id, name]) => ({ id, name }))
      .sort((a, b) => a.name.localeCompare(b.name));
  });

  protected readonly authorOptions = computed(() =>
    distinctSorted((this.revisions() ?? []).map(r => r.author)));

  protected readonly organizationOptions = computed(() =>
    distinctSorted((this.revisions() ?? []).map(r => this.organizationOf(r))));

  protected readonly totalCount = computed(() => (this.revisions() ?? []).length);
  protected readonly awaitingCount = computed(() => this.countState('IN_REVIEW'));
  protected readonly reviewingCount = computed(() => this.countState('REVIEWING'));

  protected readonly hasFilters = computed(() =>
    this.states().size > 0 || this.applications().size > 0 || this.authors().size > 0
    || this.organizations().size > 0 || !!this.search().trim());

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

  /** The maintaining organization's name; null for anything not maintained by an organization. */
  protected organizationOf(revision: MyIntegrationMethod): string | null {
    return revision.maintainer?.category === 'ORG' ? maintainerLabel(revision.maintainer) : null;
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
    this.authors.set(new Set());
    this.organizations.set(new Set());
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

  protected toggleOrganization(organization: string): void {
    this.organizations.update(set => toggled(set, organization));
  }

  protected clearFilter(filter: 'states' | 'applications' | 'authors' | 'organizations'): void {
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

  protected openDetails(revision: MyIntegrationMethod): void {
    this.closeMenu();
    this.router.navigate(['/applications', revision.applicationId, 'integration-method', revision.id, revision.revision, 'details']);
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

  protected readonly confirmConnectorName = computed(() => {
    const r = this.confirmRevision();
    return r ? (r.connectorDisplayName || r.displayName || 'Integration method') : '';
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
    this.applicationService.publishIntegrationMethod(revision.applicationId, revision.id, revision.revision).subscribe({
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
    this.applicationService.startReviewIntegrationMethod(revision.applicationId, revision.id, revision.revision).subscribe({
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
    this.applicationService.stopReviewIntegrationMethod(revision.applicationId, revision.id, revision.revision).subscribe({
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

function distinctSorted(values: (string | null)[]): string[] {
  return [...new Set(values.filter((v): v is string => !!v))].sort((a, b) => a.localeCompare(b));
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
