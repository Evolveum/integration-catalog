/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

/**
 * State of the support-portal work package backing a revision's review, as the approval dialog needs it.
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
