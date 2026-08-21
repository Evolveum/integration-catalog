/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One operation the catalog owes an external system, written down before it is attempted so that
 * an unreachable system costs a delay rather than the operation itself.
 *
 * <p>Deliberately not modelled per external system or per kind of work. The row says which system
 * it belongs to ({@link #targetSystem}), which operation of that system it is ({@link #operation})
 * and carries everything needed to perform it as opaque JSON ({@link #payload}); what that JSON
 * means is known only to the handler registered for the pair. Supporting another system, or another
 * operation of a system already supported, therefore needs no column and no change to the scheduled
 * retry - see {@code RetryableOperationHandler}.
 *
 * <p>Rows are kept after they finish rather than deleted, so "the portal was down for two days last
 * month" remains answerable from the database. {@link #attempts}, {@link #lastAttemptAt} and
 * {@link #lastError} are what makes that answer legible.
 */
@Entity
@Table(name = "pending_operation")
@Getter @Setter
public class PendingOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Which external system the operation is owed to; the retry can be narrowed to one of them. */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_system", length = 50, nullable = false)
    private ExternalSystem targetSystem;

    /**
     * Which operation of {@link #targetSystem} this is, e.g. {@code CREATE_WORK_PACKAGE}. Free text
     * rather than an enum shared by every system, because the set of operations belongs to whoever
     * integrates a system and no central type should have to be edited to add one. Together with
     * {@link #targetSystem} it selects the handler that knows how to perform and how to read
     * {@link #payload}.
     */
    @Column(name = "operation", length = 100, nullable = false)
    private String operation;

    /**
     * Everything needed to perform the operation, as JSON, in a shape defined by the handler.
     *
     * <p>What is stored is normally an identifier of what the operation is about rather than the
     * finished request: the handler reads the current state when it runs, so a task created a day
     * late describes the submission as it stands then, including anything the author changed or
     * uploaded during the outage.
     */
    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private PendingOperationStatus status = PendingOperationStatus.PENDING;

    /** How often the operation has been attempted, counting the immediate attempt on submission. */
    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** When it was last attempted, or null while it has never been. */
    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    /**
     * Why the last attempt did not succeed, for whoever has to work out whether the external system
     * is down or the catalog is asking it for something impossible. Cleared once the operation
     * succeeds, so a row that reads DONE does not also carry an error.
     */
    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;
}
