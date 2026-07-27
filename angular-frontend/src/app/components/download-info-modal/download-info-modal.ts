/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, Input, Output, EventEmitter } from '@angular/core';

/** One numbered step in the post-download help modal. */
export interface DownloadInfoStep {
  title: string;
  description?: string;
}

// Default content: the integration-method bundle download (where to copy the connector
// .jar files inside the midPoint home directory). Other downloads pass their own steps.
const BUNDLE_STEPS: DownloadInfoStep[] = [
  {
    title: 'Extract ZIP archive'
  },
  {
    title: 'Copy the connectors to midPoint home',
    description: 'Copy the connectors from the connectors directory to the connid-connectors '
      + 'directory in your midPoint home directory'
  },
  {
    title: 'Wait a few minutes for midPoint for discovery',
    description: 'MidPoint scans the directory and discovers the newly added connectors. '
      + 'Once the scan is complete, the connectors are ready to be used.'
  }
];

const BUNDLE_NOTE = 'If the package contains a samples directory, it may include sample objects '
  + 'that can be imported into midPoint';

/**
 * Post-download help modal shown after a download starts. The parent controls visibility
 * (render it inside an @if); the modal can only be dismissed via the Understood button or
 * the corner X (backdrop clicks do nothing).
 */
@Component({
  selector: 'app-download-info-modal',
  standalone: true,
  templateUrl: './download-info-modal.html',
  styleUrls: ['./download-info-modal.scss']
})
export class DownloadInfoModal {
  /** Name of the downloaded file, e.g. "sharepoint-via-rest-v2.zip". */
  @Input() fileName = '';
  /** Size of the downloaded file in bytes (null = unknown, size hidden). */
  @Input() fileSize: number | null = null;
  /** Numbered setup steps; defaults to the integration-method bundle steps. */
  @Input() steps: DownloadInfoStep[] = BUNDLE_STEPS;
  /** Optional info note under the steps (empty = hidden); defaults to the bundle samples note. */
  @Input() note = BUNDLE_NOTE;
  @Output() closed = new EventEmitter<void>();

  protected onClose(): void {
    this.closed.emit();
  }

  protected formattedSize(): string | null {
    const size = this.fileSize;
    if (size === null || size < 0) return null;
    if (size < 1024) return `${size} B`;
    if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} kB`;
    return `${(size / (1024 * 1024)).toFixed(1)} MB`;
  }
}
