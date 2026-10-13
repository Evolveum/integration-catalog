/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

/**
 * Describes a portal failure for the pending row, where it is the only account of why an operation is
 * still waiting. Every handler words this the same way, so the queue reads consistently.
 */
final class SupportTicketFailures {

    private SupportTicketFailures() {
    }

    /** The exception as a line of text, naming the type when it carries no message of its own. */
    static String reason(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + message;
    }
}
