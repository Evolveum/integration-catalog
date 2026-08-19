/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, Input, Output, EventEmitter, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApplicationService, ConnectorWithoutDownload, SupportTicket } from '../../services/application.service';

/**
 * Reusable multi-step confirmation modal for approving or rejecting an in-review
 * integration-method version. The parent controls visibility (render it inside an
 * @if), passes the display data + processing/error state, and performs the actual
 * approve/reject on the `confirm` output. This component owns the three-step flow:
 *   Step 0: connectors still missing download info (approve mode only)
 *   Step 1: the support work package behind the review
 *   Step 2: ready to confirm
 *
 * Step 0 lists the linked connectors that have no artifact yet, so the reviewer can trigger a build
 * or fill the details in by hand. While any remain the step only offers Refresh; Continue appears
 * once the list is empty.
 *
 * Step 1 is the second gate, driven by the support portal: the work package opened when the version
 * was submitted is read on entry and again on every Refresh, and approval stays disabled until
 * the portal reports the status the backend treats as the go-ahead. A version with no work
 * package (submitted before the portal integration, or while the portal was down) has nothing
 * to wait for and goes straight to the confirmable step.
 *
 * Rejection is deliberately not gated: it skips step 0, Refresh advances it as before, and the
 * ticket is shown only so the reviewer can follow the link to write the reason there.
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
  /** Emitted when the user clicks "Manual fill" for a connector. */
  @Output() manualFill = new EventEmitter<ConnectorWithoutDownload>();

  private readonly applicationService = inject(ApplicationService);

  protected readonly step = signal<number>(0);
  protected readonly successDismissed = signal<boolean>(false);

  // Step 0 state: connectors linked to this revision that have no artifact to download yet.
  protected readonly connectorsWithoutDownload = signal<ConnectorWithoutDownload[]>([]);
  protected readonly loadingConnectors = signal<boolean>(false);
  protected readonly buildingConnectors = signal<Set<number>>(new Set());
  protected readonly buildMessages = signal<Map<number, string>>(new Map());

  // Step 1 state: the support work package that gates the approval.
  protected readonly ticket = signal<SupportTicket | null>(null);
  protected readonly loading = signal<boolean>(false);
  /** Set when the ticket itself could not be read, as opposed to the portal reporting a status. */
  protected readonly lookupError = signal<string>('');

  protected readonly ticketId = computed(() => this.ticket()?.ticketId ?? null);
  protected readonly ticketUrl = computed(() => this.ticket()?.url ?? null);
  protected readonly ticketStatus = computed(() => this.ticket()?.status ?? null);

  ngOnInit(): void {
    if (this.mode === 'approve') {
      this.loadConnectorsWithoutDownload();
    } else {
      // A rejection has no build prerequisites, so step 0 is skipped entirely.
      this.enterTicketStep();
    }
  }

  /**
   * Advances the flow. On step 0 the template only wires this to Continue once no connector is
   * missing its build information, so there is nothing left to check and it moves straight on. On
   * step 1 it re-reads the work package, and for an approval that is the whole gate — the modal
   * only advances once the portal reports the awaited status. A rejection advances regardless,
   * keeping its original behaviour.
   */
  protected refresh(): void {
    if (this.step() === 0) {
      this.enterTicketStep();
      return;
    }
    if (this.mode === 'reject') {
      this.step.set(2);
      return;
    }
    this.loadTicket();
  }

  /** Moves on to the ticket gate and reads the work package it is waiting on. */
  private enterTicketStep(): void {
    this.step.set(1);
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
