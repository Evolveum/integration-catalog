/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Reusable confirmation modal for starting a review of an in-review integration-method version.
 * The parent controls visibility (render it inside an @if), passes the display data +
 * processing/error state, and performs the actual state transition on the `confirm` output.
 * Kept standalone/self-contained so it can be reused from other screens that trigger a review.
 */
@Component({
  selector: 'app-start-review-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './start-review-modal.html',
  styleUrls: ['./start-review-modal.scss']
})
export class StartReviewModal {
  /** Human-readable version label, e.g. "v2". Shown in the heading. */
  @Input() versionLabel = '';
  @Input() processing = false;
  @Input() error = '';
  @Output() confirm = new EventEmitter<void>();
  @Output() cancel = new EventEmitter<void>();

  protected onConfirm(): void {
    if (this.processing) return;
    this.confirm.emit();
  }

  protected onCancel(): void {
    if (this.processing) return;
    this.cancel.emit();
  }
}
