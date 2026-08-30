/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, EventEmitter, Input, OnInit, Output, computed, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ApplicationService, SupportTicket } from '../../services/application.service';

/**
 * Confirms that a revision has been put in front of a reviewer, and hands the author its support
 * ticket.
 *
 * <p>One component for every way a revision reaches a reviewer - a first publish, a correction, and
 * an upgrade to a new version - because they differ only in what they are called. Each one ends the
 * same way for the author: the submission is out of their hands, and the ticket is where the rest of
 * the conversation happens. Only the title and subtitle are passed in.
 *
 * <p>The ticket is read here rather than by each caller, since it is read the same way every time
 * and is this modal's own content. A submission whose ticket cannot be read - the portal switched
 * off, or unreachable - shows the confirmation without the panel: what the author did succeeded
 * regardless, and the ticket is also on the method's own page once there is one.
 */
@Component({
  selector: 'app-submission-success-modal',
  standalone: true,
  templateUrl: './submission-success-modal.html',
  styleUrls: ['./submission-success-modal.scss']
})
export class SubmissionSuccessModal implements OnInit {

  /** Headline, e.g. "Integration method submitted for review". */
  @Input() title = '';

  /** Line under the headline; omitted when blank. */
  @Input() subtitle = '';

  /** The revision whose ticket is shown - all three parts are needed to ask for it. */
  @Input() appId = '';
  @Input() methodId = '';
  @Input() revision = '';

  /** The primary button. What "done" means is the caller's to decide. */
  @Output() done = new EventEmitter<void>();

  /** The corner cross, for a caller that has somewhere to go back to. */
  @Output() dismissed = new EventEmitter<void>();

  protected readonly ticket = signal<SupportTicket | null>(null);
  protected readonly ticketId = computed(() => this.ticket()?.ticketId ?? null);
  protected readonly ticketUrl = computed(() => this.ticket()?.url ?? null);

  /** Message of the "copied" toast, and whether it shows at all. */
  protected readonly copiedNotice = signal<string | null>(null);

  constructor(private applicationService: ApplicationService) {}

  ngOnInit(): void {
    this.loadTicket();
  }

  /**
   * Reads the work package opened for the revision.
   *
   * The backend opens it while the submitting request is still being served, so it is already
   * recorded by the time this modal appears and there is nothing to wait for. A failure is logged
   * and leaves the panel out.
   */
  private loadTicket(): void {
    if (!this.appId || !this.methodId || !this.revision) {
      return;
    }
    this.applicationService.getSupportTicket(this.appId, this.methodId, this.revision).subscribe({
      next: (ticket) => this.ticket.set(ticket),
      error: (error: HttpErrorResponse) => {
        this.ticket.set(null);
        console.error('Could not read the support ticket of the submission:', error);
      }
    });
  }

  protected copyTicketLink(): void {
    const url = this.ticketUrl();
    if (!url) {
      return;
    }
    navigator.clipboard.writeText(url).then(() => {
      this.copiedNotice.set('Ticket link copied to clipboard');
      setTimeout(() => this.copiedNotice.set(null), 3000);
    });
  }
}
