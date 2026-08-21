/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, Input, Output, EventEmitter, signal, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApplicationService, ConnectorWithoutDownload } from '../../services/application.service';

/**
 * Reusable multi-step confirmation modal for approving or rejecting an in-review
 * integration-method version. The parent controls visibility (render it inside an
 * @if), passes the display data + processing/error state, and performs the actual
 * approve/reject on the `confirm` output. This component owns the multi-step flow:
 *   Step 0: Connectors without download info (approve mode only)
 *   Step 1: Support ticket still open (pending notice → Refresh → ready → Confirm)
 *   Step 2: Ready to confirm
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
  @Input() connectorName = '';
  @Input() versionLabel = '';
  @Input() submittedBy = '';
  @Input() processing = false;
  @Input() error = '';
  // Data needed to call the backend for connectors without download info.
  @Input() appId = '';
  @Input() methodId = '';
  @Input() revision = '';
  @Output() confirm = new EventEmitter<void>();
  @Output() cancel = new EventEmitter<void>();
  /** Emitted when the user clicks "Manual fill" for a connector. */
  @Output() manualFill = new EventEmitter<ConnectorWithoutDownload>();

  protected readonly step = signal<number>(0);
  protected readonly successDismissed = signal<boolean>(false);
  protected readonly connectorsWithoutDownload = signal<ConnectorWithoutDownload[]>([]);
  protected readonly loadingConnectors = signal<boolean>(false);
  protected readonly buildingConnectors = signal<Set<number>>(new Set());
  protected readonly buildMessages = signal<Map<number, string>>(new Map());

  // Hardcoded ticket details for now (to be wired to the support portal later).
  protected readonly ticketId = '1239';
  protected readonly relatedTicketId = '10452';
  protected readonly ticketClosedDate = 'Jun 3, 2026';
  protected readonly ticketUrl = 'https://support.evolveum.com/tickets/1239';

  constructor(private applicationService: ApplicationService) {}

  ngOnInit(): void {
    // Only load connectors without download info for approve mode.
    if (this.mode === 'approve' && this.appId && this.methodId && this.revision) {
      this.loadConnectorsWithoutDownload();
    } else if (this.mode === 'approve') {
      // No method data provided, skip step 0 and go to step 1.
      this.step.set(1);
    } else {
      // Reject mode skips step 0 entirely.
      this.step.set(1);
    }
  }

  private loadConnectorsWithoutDownload(): void {
    this.loadingConnectors.set(true);
    this.applicationService.getConnectorsWithoutDownload(this.appId, this.methodId, this.revision).subscribe({
      next: (connectors) => {
        this.connectorsWithoutDownload.set(connectors);
        this.loadingConnectors.set(false);
      },
      error: (err) => {
        console.error('Failed to load connectors without download info', err);
        this.loadingConnectors.set(false);
      }
    });
  }

  /** Trigger build for a single connector. */
  protected triggerBuild(connector: ConnectorWithoutDownload): void {
    this.buildingConnectors.update(set => {
      const next = new Set(set);
      next.add(connector.connectorId);
      return next;
    });
    this.buildMessages.update(map => {
      const next = new Map(map);
      next.set(connector.connectorId, 'Triggering build...');
      return next;
    });

    this.applicationService.triggerBuildForConnector(this.methodId, {
      className: connector.className || null,
      version: connector.version || null,
      integrationMethodRevision: connector.integrationMethodRevision,
      connectorVersionId: connector.connectorVersionId,
      connectorVersionRevision: connector.connectorVersionRevision
    }).subscribe({
      next: (result) => {
        this.buildingConnectors.update(set => {
          const next = new Set(set);
          next.delete(connector.connectorId);
          return next;
        });
        this.buildMessages.update(map => {
          const next = new Map(map);
          next.set(connector.connectorId, 'Build triggered. Status will update after the build completes.');
          return next;
        });
      },
      error: (err) => {
        console.error('Failed to trigger build', err);
        this.buildingConnectors.update(set => {
          const next = new Set(set);
          next.delete(connector.connectorId);
          return next;
        });
        this.buildMessages.update(map => {
          const next = new Map(map);
          next.set(connector.connectorId, 'Failed to trigger build.');
          return next;
        });
      }
    });
  }

  /** Refresh the connectors without download list. */
  protected refreshConnectors(): void {
    this.loadConnectorsWithoutDownload();
  }

  /** "Refresh to check again" advances from step 0 (connectors) or step 1 (ticket) to the next step. */
  protected refresh(): void {
    if (this.step() === 0) {
      // Move past the connectors-without-download step.
      this.step.set(1);
    } else {
      // Step 1 (ticket) → step 2 (ready to confirm).
      this.step.set(2);
    }
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

  /** Check if there are still connectors without download info. */
  protected hasConnectorsWithoutDownload(): boolean {
    return this.connectorsWithoutDownload().length > 0;
  }

  /** Get build message for a connector. */
  protected getBuildMessage(connectorId: number): string | null {
    return this.buildMessages().get(connectorId) ?? null;
  }

  /** Check if a connector is currently being built. */
  protected isBuilding(connectorId: number): boolean {
    return this.buildingConnectors().has(connectorId);
  }

  /** Emit event to open the manual fill popup modal in the parent. */
  protected openManualFill(connector: ConnectorWithoutDownload): void {
    this.manualFill.emit(connector);
  }

  /** Called by parent after manual fill succeeds - refresh the connector list. */
  onManualFillSuccess(): void {
    this.loadConnectorsWithoutDownload();
  }
}
