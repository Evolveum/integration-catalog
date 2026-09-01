/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service.retry;

import com.evolveum.midpoint.integration.catalog.configuration.PendingOperationProperties;
import com.evolveum.midpoint.integration.catalog.object.ExternalSystem;
import com.evolveum.midpoint.integration.catalog.object.PendingOperation;
import com.evolveum.midpoint.integration.catalog.object.PendingOperationStatus;
import com.evolveum.midpoint.integration.catalog.repository.PendingOperationRepository;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PendingOperationStore}, which owns what a row says after an attempt. The
 * repository is mocked, so no database is needed; each test asserts the state one outcome leaves
 * behind, since that state is the only thing the scheduled retry goes on.
 */
class PendingOperationStoreTest {

    private static final long ROW_ID = 7L;

    private final PendingOperationRepository repository = mock(PendingOperationRepository.class);

    /** A store whose configured attempt limit is {@code maxAttempts}; 0 means retry indefinitely. */
    private PendingOperationStore storeAllowing(int maxAttempts) {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new PendingOperationStore(repository,
                new PendingOperationProperties(null, 0, maxAttempts));
    }

    /** A row as it stands after {@code attempts} attempts, still pending. */
    private PendingOperation pendingAfter(int attempts) {
        PendingOperation pending = new PendingOperation();
        pending.setId(ROW_ID);
        pending.setTargetSystem(ExternalSystem.OPENPROJECT);
        pending.setOperation("OPEN_WORK_PACKAGE");
        pending.setPayload("{}");
        pending.setStatus(PendingOperationStatus.PENDING);
        pending.setAttempts(attempts);
        when(repository.findById(ROW_ID)).thenReturn(Optional.of(pending));
        return pending;
    }

    @Test
    void settlesAnOperationThatSucceeded() {
        PendingOperation pending = pendingAfter(0);
        pending.setLastError("could not reach the portal");

        storeAllowing(0).attempted(ROW_ID, OperationOutcome.COMPLETED, null);

        assertThat(pending.getStatus()).isEqualTo(PendingOperationStatus.DONE);
        assertThat(pending.getAttempts()).isEqualTo(1);
        assertThat(pending.getLastAttemptAt()).isNotNull();
        assertThat(pending.getLastError()).isNull();
    }

    @Test
    void leavesAFailedOperationPendingSoTheRetryFindsIt() {
        PendingOperation pending = pendingAfter(3);

        storeAllowing(0).attempted(ROW_ID, OperationOutcome.RETRY, "connection refused");

        assertThat(pending.getStatus()).isEqualTo(PendingOperationStatus.PENDING);
        assertThat(pending.getAttempts()).isEqualTo(4);
        assertThat(pending.getLastError()).isEqualTo("connection refused");
    }

    @Test
    void keepsRetryingIndefinitelyByDefault() {
        PendingOperation pending = pendingAfter(500);

        storeAllowing(0).attempted(ROW_ID, OperationOutcome.RETRY, "still down");

        assertThat(pending.getStatus()).isEqualTo(PendingOperationStatus.PENDING);
    }

    @Test
    void abandonsAnOperationOnceTheConfiguredAttemptsRunOut() {
        PendingOperation pending = pendingAfter(2);

        storeAllowing(3).attempted(ROW_ID, OperationOutcome.RETRY, "still down");

        assertThat(pending.getAttempts()).isEqualTo(3);
        assertThat(pending.getStatus()).isEqualTo(PendingOperationStatus.ABANDONED);
    }

    @Test
    void closesAnOperationWhoseSubjectIsGone() {
        PendingOperation pending = pendingAfter(0);

        storeAllowing(0).attempted(ROW_ID, OperationOutcome.OBSOLETE, "revision no longer exists");

        assertThat(pending.getStatus()).isEqualTo(PendingOperationStatus.OBSOLETE);
    }

    @Test
    void recordsAnOperationAsPending() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PendingOperationStore store = storeAllowing(0);

        PendingOperation pending = store.record(ExternalSystem.OPENPROJECT, "OPEN_WORK_PACKAGE", "{\"a\":1}");

        assertThat(pending.getStatus()).isEqualTo(PendingOperationStatus.PENDING);
        assertThat(pending.getAttempts()).isZero();
        assertThat(pending.getCreatedAt()).isNotNull();
        assertThat(pending.getPayload()).isEqualTo("{\"a\":1}");
    }

    @Test
    void keepsALongFailureShortEnoughToStore() {
        PendingOperation pending = pendingAfter(0);

        storeAllowing(0).attempted(ROW_ID, OperationOutcome.RETRY, "x".repeat(5000));

        assertThat(pending.getLastError()).hasSize(2000);
    }

    @Test
    void defaultsToAnHourlyRetryOfAHundredOperationsWithNoAttemptLimit() {
        PendingOperationProperties defaults = new PendingOperationProperties(null, 0, 0);

        assertThat(defaults.cron()).isEqualTo("0 0 * * * *");
        assertThat(defaults.batchSize()).isEqualTo(100);
        assertThat(defaults.isExhausted(Integer.MAX_VALUE)).isFalse();
    }
}
