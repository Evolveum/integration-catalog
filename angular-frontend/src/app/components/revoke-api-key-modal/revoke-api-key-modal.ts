/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, EventEmitter, Input, Output } from '@angular/core';

/** Confirms a revocation, which takes effect immediately and cannot be undone. */
@Component({
  selector: 'app-revoke-api-key-modal',
  standalone: true,
  templateUrl: './revoke-api-key-modal.html',
  styleUrls: ['./revoke-api-key-modal.scss']
})
export class RevokeApiKeyModal {
  /** True while the revocation is in flight: the modal stays open but cannot be confirmed twice. */
  @Input() busy = false;

  @Output() closed = new EventEmitter<void>();
  @Output() confirmed = new EventEmitter<void>();

  protected onClose(): void {
    this.closed.emit();
  }

  protected onConfirm(): void {
    if (!this.busy) {
      this.confirmed.emit();
    }
  }
}
