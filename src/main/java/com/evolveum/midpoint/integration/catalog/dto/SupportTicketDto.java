/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

/**
 * State of the support-portal work package backing a revision's review, as the approval dialog
 * needs it.
 *
 * @param configured    whether the catalog is wired to a support portal at all; when false the
 *                      reviewer is not held up by a ticket check
 * @param ticketId      work package id, null when none was opened for this revision
 * @param url           browser link to the work package, null when there is none
 * @param status        current status title as the portal reports it, e.g. "Resolved"; null when
 *                      there is no ticket or the portal could not be reached
 * @param approvalReady whether the status matches the one configured as the go-ahead for approval
 * @param error         why the status is unknown, for display; null when the lookup succeeded
 */
public record SupportTicketDto(
        boolean configured,
        Integer ticketId,
        String url,
        String status,
        boolean approvalReady,
        String error
) {
}
