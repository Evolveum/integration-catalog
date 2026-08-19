/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, Input, Output, EventEmitter, OnChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApplicationService, ConnectorWithoutDownload } from '../../services/application.service';

/**
 * Standalone popup modal for manually filling in connector build information.
 * The parent passes the connector data and listens for submit/cancel events.
 * This component calls verify + continue sequentially via the API.
 */
@Component({
  selector: 'app-manual-fill-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './manual-fill-modal.html',
  styleUrls: ['./manual-fill-modal.scss']
})
export class ManualFillModal implements OnChanges {
  @Input({ required: true }) connector!: ConnectorWithoutDownload;
  @Input() methodId = '';

  /** Emitted when the form is submitted successfully (verify + complete succeeded). */
  @Output() submit = new EventEmitter<void>();
  /** Emitted when the user clicks Cancel. */
  @Output() cancel = new EventEmitter<void>();
  /** Emitted when the submit fails with an error message. */
  @Output() error = new EventEmitter<string>();

  // Form fields
  bundleName = '';
  version = '';
  downloadLink = '';
  className = '';

  // UI state
  submitting = false;
  errorMessage: string | null = null;
  /** True if an error occurred during submission - prevents submit event from firing. */
  private hadError = false;

  constructor(private applicationService: ApplicationService) {}

  ngOnChanges(): void {
    // Pre-populate form when connector input changes (Inputs are not set in constructor).
    // Only populate empty fields - don't overwrite user-entered values on re-renders.
    if (this.connector) {
      if (!this.bundleName) {
        this.bundleName = this.connector.bundleName || '';
      }
      if (!this.version) {
        this.version = this.connector.version || '';
      }
      if (!this.className) {
        this.className = this.connector.className || '';
      }
    }
  }

  /** Submit the form: calls verify then complete sequentially. */
  onSubmit(): void {
    if (!this.bundleName || !this.version || !this.className) {
      this.errorMessage = 'Bundle name, version, and class name are required.';
      return;
    }

    this.submitting = true;
    this.errorMessage = null;
    this.hadError = false;

    const verifyPayload = {
      bundleName: this.bundleName,
      version: this.version,
      className: this.className,
      integrationMethodRevision: this.connector.integrationMethodRevision,
      connectorVersionId: this.connector.connectorVersionId,
      connectorVersionRevision: this.connector.connectorVersionRevision
    };

    // Step 1: verify
    this.applicationService.verifyBundle(this.methodId, verifyPayload).subscribe({
      next: (result) => {
        // Step 2: continue
        this.applicationService.continueBuild(this.methodId, {
          connectorBundle: this.bundleName,
          connectorVersion: this.version,
          integrationMethodRevision: this.connector.integrationMethodRevision,
          publishTime: null,
          downloadLink: this.downloadLink || null,
          connectorClass: this.className,
          capability: null,
          connectorVersionId: this.connector.connectorVersionId,
          connectorVersionRevision: this.connector.connectorVersionRevision
        }).subscribe({
          next: () => {
            // Only emit submit if no error occurred
            if (!this.hadError) {
              this.submit.emit();
            }
          },
          error: (err) => {
            console.error('Failed to complete build', err);
            this.hadError = true;
            this.submitting = false;
            try {
              const message = err.error?.detail || err.error?.message || err.message || 'Failed to complete build.';
              this.errorMessage = String(message);
              this.error.emit(this.errorMessage);
            } catch {
              this.errorMessage = 'Failed to complete build.';
              this.error.emit(this.errorMessage);
            }
            // Do NOT emit submit - keep modal open
          }
        });
      },
      error: (err) => {
        console.error('Failed to verify bundle', err);
        this.hadError = true;
        this.submitting = false;
        try {
          const message = err.error?.detail || err.error?.message || err.message || 'Verification failed.';
          this.errorMessage = String(message);
          this.error.emit(this.errorMessage);
        } catch {
          this.errorMessage = 'Verification failed.';
          this.error.emit(this.errorMessage);
        }
        // Do NOT emit submit - keep modal open
      }
    });
  }

  onCancel(): void {
    this.cancel.emit();
  }
}
