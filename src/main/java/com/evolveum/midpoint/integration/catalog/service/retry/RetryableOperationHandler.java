/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service.retry;

import com.evolveum.midpoint.integration.catalog.object.ExternalSystem;

import java.util.function.Function;

/**
 * Knows how to carry out one kind of operation against one external system, and is asked to do so
 * both when the operation first arises and on every later retry.
 *
 * <p>This is the whole extension point. A new external system, or a new operation of one already
 * integrated, is a bean implementing this interface: {@link PendingOperationService} finds it by
 * {@link #system()} and {@link #operation()}, hands it the payload it declared through
 * {@link #payloadType()}, and reads an {@link OperationResult} back. Neither the table, the
 * repository nor the scheduled job has to know anything about it.
 *
 * <p>Two obligations follow from being called on a retry, possibly days later:
 *
 * <ul>
 *   <li><b>Read current state.</b> The payload should identify what the operation is about rather
 *       than freeze the request that was going to be sent, so that an operation carried out late
 *       reflects the catalog as it stands then.</li>
 *   <li><b>Be repeatable.</b> An attempt may have partly succeeded before failing, and the whole
 *       operation is then attempted again. A handler has to notice what is already done - by
 *       reading it back from the external system, or from what the first attempt recorded in the
 *       catalog - instead of duplicating it.</li>
 * </ul>
 *
 * @param <T> the payload this handler stores and reads back, deserialised from JSON by the caller
 */
public interface RetryableOperationHandler<T> {

    /** The external system this handler talks to. */
    ExternalSystem system();

    /**
     * Which operation of {@link #system()} this handler performs, e.g. {@code CREATE_WORK_PACKAGE}.
     * Stored verbatim on the row, so it may not be changed once operations have been written under
     * it - the rows would no longer find their handler.
     */
    String operation();

    /** The type the stored JSON payload is read back as and handed to {@link #execute}. */
    Class<T> payloadType();

    /**
     * Performs the operation once.
     *
     * <p>May throw: an unexpected failure is recorded against the row, with the exception's message
     * as the reason, and read as {@link OperationOutcome#RETRY} - so nothing is lost by not catching
     * it. Returning {@link OperationResult#retry} explicitly is for the failures a handler does
     * expect; say in the reason what the log would have said, because that reason is what the row
     * carries and what anyone looking at the table has to go on.
     */
    OperationResult execute(T payload) throws Exception;

    /**
     * A handler assembled from its four parts, for the common case where performing the operation
     * is a call into an existing service and no state of its own is needed.
     *
     * @param system      which external system, see {@link #system()}
     * @param operation   which operation of it, see {@link #operation()}
     * @param payloadType what the payload is read back as, see {@link #payloadType()}
     * @param execute     performs the operation, see {@link #execute}
     */
    static <T> RetryableOperationHandler<T> of(ExternalSystem system, String operation,
                                               Class<T> payloadType,
                                               Function<T, OperationResult> execute) {
        return new RetryableOperationHandler<>() {

            @Override
            public ExternalSystem system() {
                return system;
            }

            @Override
            public String operation() {
                return operation;
            }

            @Override
            public Class<T> payloadType() {
                return payloadType;
            }

            @Override
            public OperationResult execute(T payload) {
                return execute.apply(payload);
            }
        };
    }
}
