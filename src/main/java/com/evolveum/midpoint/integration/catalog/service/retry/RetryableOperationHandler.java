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
     */
    OperationResult execute(T payload) throws Exception;

    /**
     * A handler assembled from its four parts, for the common case where performing the operation
     * is a call into an existing service and no state of its own is needed.
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
