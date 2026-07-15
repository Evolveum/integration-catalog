/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, Input, Output, EventEmitter, signal } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Reusable two-step confirmation modal for approving or rejecting an in-review
 * integration-method version. The parent controls visibility (render it inside an
 * @if), passes the display data + processing/error state, and performs the actual
 * approve/reject on the `confirm` output. This component owns the two-step flow
 * (pending notice → Refresh → ready → Confirm).
 */
@Component({
  selector: 'app-approval-confirm-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './approval-confirm-modal.html',
  styleUrls: ['./approval-confirm-modal.scss']
})
export class ApprovalConfirmModal {
  @Input({ required: true }) mode!: 'approve' | 'reject';
  @Input() connectorName = '';
  @Input() versionLabel = '';
  @Input() submittedBy = '';
  @Input() processing = false;
  @Input() error = '';
  @Output() confirm = new EventEmitter<void>();
  @Output() cancel = new EventEmitter<void>();

  protected readonly step = signal<number>(1);
  protected readonly successDismissed = signal<boolean>(false);

  // Hardcoded ticket details for now (to be wired to the support portal later).
  protected readonly ticketId = '1239';
  protected readonly relatedTicketId = '10452';
  protected readonly ticketClosedDate = 'Jun 3, 2026';
  protected readonly ticketUrl = 'https://support.evolveum.com/tickets/1239';

  /** "Refresh to check again" advances the modal to its ready (confirmable) step. */
  protected refresh(): void {
    this.step.set(2);
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
