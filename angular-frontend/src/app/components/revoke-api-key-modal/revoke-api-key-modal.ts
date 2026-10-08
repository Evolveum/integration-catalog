/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, EventEmitter, Input, Output } from '@angular/core';

/**
 * Confirms an action on an API key. The defaults are the revocation, which takes effect immediately
 * and cannot be undone; the rotation passes its own texts and the primary variant.
 */
@Component({
  selector: 'app-revoke-api-key-modal',
  standalone: true,
  templateUrl: './revoke-api-key-modal.html',
  styleUrls: ['./revoke-api-key-modal.scss']
})
export class RevokeApiKeyModal {
  /** True while the action is in flight: the modal stays open but cannot be confirmed twice. */
  @Input() busy = false;
  @Input() title = 'Revoke API key?';
  @Input() text = 'This action takes effect immediately and cannot be undone and requests authenticated with '
    + 'this key will start failing as soon as it is revoked.';
  @Input() confirmLabel = 'Revoke API key';
  @Input() busyLabel = 'Revoking...';
  /** Font Awesome icon in the circle next to the title. */
  @Input() icon = 'fa-exclamation';
  /** 'danger' for destructive actions, 'primary' for the rest. */
  @Input() variant: 'danger' | 'primary' = 'danger';

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
