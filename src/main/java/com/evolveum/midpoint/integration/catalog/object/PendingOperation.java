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
     * Which operation of {@link #targetSystem} this is, e.g. {@code CREATE_WORK_PACKAGE}.
     */
    @Column(name = "operation", length = 100, nullable = false)
    private String operation;

    /**
     * Everything needed to perform the operation, as JSON, in a shape defined by the handler.
     */
    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private PendingOperationStatus status = PendingOperationStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;
}
