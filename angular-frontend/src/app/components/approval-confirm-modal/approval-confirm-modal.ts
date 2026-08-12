/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, Input, Output, EventEmitter, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApplicationService, SupportTicket } from '../../services/application.service';

/**
 * Reusable two-step confirmation modal for approving or rejecting an in-review
 * integration-method version. The parent controls visibility (render it inside an
 * @if), passes the display data + processing/error state, and performs the actual
 * approve/reject on the `confirm` output. This component owns the two-step flow
 * (pending notice → Refresh → ready → Confirm).
 *
 * The two steps are driven by the support portal: the work package opened when the version
 * was submitted is read on open and again on every Refresh, and approval stays disabled until
 * the portal reports the status the backend treats as the go-ahead. A version with no work
 * package (submitted before the portal integration, or while the portal was down) has nothing
 * to wait for and goes straight to the confirmable step.
 *
 * Rejection is deliberately not gated: Refresh advances it as before, and the ticket is shown
 * only so the reviewer can follow the link to write the reason there.
 */
@Component({
  selector: 'app-approval-confirm-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './approval-confirm-modal.html',
  styleUrls: ['./approval-confirm-modal.scss']
})
export class ApprovalConfirmModal implements OnInit {
  @Input({ required: true }) mode!: 'approve' | 'reject';
  @Input({ required: true }) appId!: string;
  @Input({ required: true }) methodId!: string;
  @Input({ required: true }) revision!: string;
  @Input() connectorName = '';
  @Input() versionLabel = '';
  @Input() submittedBy = '';
  @Input() processing = false;
  @Input() error = '';
  @Output() confirm = new EventEmitter<void>();
  @Output() cancel = new EventEmitter<void>();

  private readonly applicationService = inject(ApplicationService);

  protected readonly step = signal<number>(1);
  protected readonly successDismissed = signal<boolean>(false);
  protected readonly ticket = signal<SupportTicket | null>(null);
  protected readonly loading = signal<boolean>(false);
  /** Set when the ticket itself could not be read, as opposed to the portal reporting a status. */
  protected readonly lookupError = signal<string>('');

  protected readonly ticketId = computed(() => this.ticket()?.ticketId ?? null);
  protected readonly ticketUrl = computed(() => this.ticket()?.url ?? null);
  protected readonly ticketStatus = computed(() => this.ticket()?.status ?? null);

  ngOnInit(): void {
    this.loadTicket();
  }

  /**
   * "Refresh to check again" re-reads the work package. For an approval that is the whole gate:
   * the modal only advances once the portal reports the awaited status. A rejection advances
   * regardless, keeping its original behaviour.
   */
  protected refresh(): void {
    if (this.mode === 'reject') {
      this.step.set(2);
      return;
    }
    this.loadTicket();
  }

  private loadTicket(): void {
    if (this.loading()) return;
    this.loading.set(true);
    this.lookupError.set('');
    this.applicationService.getSupportTicket(this.appId, this.methodId, this.revision).subscribe({
      next: (ticket) => {
        this.loading.set(false);
        this.ticket.set(ticket);
        this.lookupError.set(ticket.error ?? '');
        // Only the approval is gated; a rejection keeps its manual two-step flow.
        if (this.mode === 'approve' && ticket.approvalReady) {
          this.step.set(2);
        }
      },
      error: (err) => {
        this.loading.set(false);
        console.error('Support ticket lookup failed', err);
        // Leave the modal on step 1: without an answer from the portal the reviewer should not
        // be waved through, but the reason is shown so it is clear why the button stays disabled.
        this.lookupError.set('The support ticket could not be loaded.');
      }
    });
  }

  protected dismissSuccess(): void {
    this.successDismissed.set(true);
  }

  protected onConfirm(): void {
    this.confirm.emit();
  }

  protected onCancel(): void {
    if (this.processing) return;
    this.cancel.emit();
  }
}
