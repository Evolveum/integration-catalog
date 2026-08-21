/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service.retry;

import com.evolveum.midpoint.integration.catalog.object.ExternalSystem;
import com.evolveum.midpoint.integration.catalog.object.PendingOperation;
import com.evolveum.midpoint.integration.catalog.object.PendingOperationStatus;
import com.evolveum.midpoint.integration.catalog.service.IntegrationMethodSubmittedEvent;
import com.evolveum.midpoint.integration.catalog.service.SubmissionFlow;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PendingOperationService}, the part of the retry mechanism that decides when
 * an operation is written down, when it is attempted and what its outcome is recorded as. The store
 * is mocked, so no database is needed; each test drives one way an attempt can go and asserts what
 * the queue is left saying about it.
 */
class PendingOperationServiceTest {

    private static final String OPERATION = "OPEN_WORK_PACKAGE";
    private static final long ROW_ID = 42L;

    private static final IntegrationMethodSubmittedEvent EVENT = new IntegrationMethodSubmittedEvent(
            UUID.fromString("11111111-2222-3333-4444-555555555555"), "1.0", SubmissionFlow.CREATE, null);

    private PendingOperationStore store;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        store = mock(PendingOperationStore.class);
        objectMapper = new ObjectMapper();
        when(store.record(any(), anyString(), anyString()))
                .thenAnswer(invocation -> row(invocation.getArgument(2)));
    }

    /** A recorded row as the store would return it, carrying the payload it was given. */
    private static PendingOperation row(String payload) {
        PendingOperation pending = new PendingOperation();
        pending.setId(ROW_ID);
        pending.setTargetSystem(ExternalSystem.OPENPROJECT);
        pending.setOperation(OPERATION);
        pending.setPayload(payload);
        pending.setStatus(PendingOperationStatus.PENDING);
        return pending;
    }

    /** A service knowing one handler, which answers with {@code result} and remembers its payload. */
    private PendingOperationService serviceReturning(OperationResult result,
                                                     AtomicReference<IntegrationMethodSubmittedEvent> seen) {
        return new PendingOperationService(store, objectMapper, List.of(
                RetryableOperationHandler.of(ExternalSystem.OPENPROJECT, OPERATION,
                        IntegrationMethodSubmittedEvent.class, payload -> {
                            seen.set(payload);
                            return result;
                        })));
    }

    @Test
    void writesTheOperationDownBeforeAttemptingIt() {
        AtomicReference<IntegrationMethodSubmittedEvent> seen = new AtomicReference<>();
        List<String> order = new ArrayList<>();
        when(store.record(any(), anyString(), anyString())).thenAnswer(invocation -> {
            order.add("record");
            return row(invocation.getArgument(2));
        });
        PendingOperationService service = new PendingOperationService(store, objectMapper, List.of(
                RetryableOperationHandler.of(ExternalSystem.OPENPROJECT, OPERATION,
                        IntegrationMethodSubmittedEvent.class, payload -> {
                            order.add("attempt");
                            seen.set(payload);
                            return OperationResult.completed();
                        })));

        service.submit(ExternalSystem.OPENPROJECT, OPERATION, EVENT);

        // The whole point: the row is durable before the external system is touched, so a process
        // killed during the call still leaves something for the retry to find.
        assertThat(order).containsExactly("record", "attempt");
    }

    @Test
    void handsTheHandlerBackTheSubmittedPayload() {
        AtomicReference<IntegrationMethodSubmittedEvent> seen = new AtomicReference<>();

        serviceReturning(OperationResult.completed(), seen).submit(ExternalSystem.OPENPROJECT, OPERATION, EVENT);

        assertThat(seen.get()).isEqualTo(EVENT);
    }

    @Test
    void settlesAnOperationTheExternalSystemTook() {
        serviceReturning(OperationResult.completed(), new AtomicReference<>())
                .submit(ExternalSystem.OPENPROJECT, OPERATION, EVENT);

        verify(store).attempted(ROW_ID, OperationOutcome.COMPLETED, null);
    }

    @Test
    void keepsAnOperationTheExternalSystemRefused() {
        serviceReturning(OperationResult.retry("the portal is unreachable"), new AtomicReference<>())
                .submit(ExternalSystem.OPENPROJECT, OPERATION, EVENT);

        // RETRY is what leaves the row pending, which is what the scheduled run looks for, and the
        // reason travels with it so the row says why rather than only that.
        verify(store).attempted(ROW_ID, OperationOutcome.RETRY, "the portal is unreachable");
        verify(store, never()).abandon(any(), anyString());
    }

    @Test
    void recordsWhyAHandlerGaveUpOnAnOperation() {
        serviceReturning(OperationResult.obsolete("the revision was superseded"), new AtomicReference<>())
                .submit(ExternalSystem.OPENPROJECT, OPERATION, EVENT);

        // A terminal row with no reason reads like a lost operation; the reason is what tells anyone
        // reading the table that nothing was missed.
        verify(store).attempted(ROW_ID, OperationOutcome.OBSOLETE, "the revision was superseded");
    }

    @Test
    void retriesAnOperationItsHandlerAnsweredNothingFor() {
        PendingOperationService service = new PendingOperationService(store, objectMapper, List.of(
                RetryableOperationHandler.of(ExternalSystem.OPENPROJECT, OPERATION,
                        IntegrationMethodSubmittedEvent.class, payload -> null)));

        service.submit(ExternalSystem.OPENPROJECT, OPERATION, EVENT);

        // Nothing was said about whether it happened, so the safe reading is that it did not.
        verify(store).attempted(eq(ROW_ID), eq(OperationOutcome.RETRY), contains("no result"));
    }

    @Test
    void treatsAnUnexpectedFailureAsWorthRetrying() {
        PendingOperationService service = new PendingOperationService(store, objectMapper, List.of(
                RetryableOperationHandler.of(ExternalSystem.OPENPROJECT, OPERATION,
                        IntegrationMethodSubmittedEvent.class, payload -> {
                            throw new IllegalStateException("portal unreachable");
                        })));

        service.submit(ExternalSystem.OPENPROJECT, OPERATION, EVENT);

        verify(store).attempted(eq(ROW_ID), eq(OperationOutcome.RETRY), contains("portal unreachable"));
    }

    @Test
    void doesNotAttemptAnOperationItCouldNotWriteDown() {
        AtomicReference<IntegrationMethodSubmittedEvent> seen = new AtomicReference<>();
        when(store.record(any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("database is down"));

        serviceReturning(OperationResult.completed(), seen).submit(ExternalSystem.OPENPROJECT, OPERATION, EVENT);

        // An operation performed without a row is one nobody could ever check on, so it is not
        // performed at all. The caller is not failed for it either.
        assertThat(seen.get()).isNull();
    }

    @Test
    void abandonsAnOperationNoHandlerAnswersFor() {
        PendingOperationService service = new PendingOperationService(store, objectMapper, List.of());

        service.submit(ExternalSystem.OPENPROJECT, OPERATION, EVENT);

        // Retrying it forever would only bury whatever else is pending behind it.
        verify(store).abandon(eq(ROW_ID), contains("No handler"));
        verify(store, never()).attempted(any(), any(), any());
    }

    @Test
    void abandonsAnOperationWhosePayloadCannotBeRead() {
        AtomicReference<IntegrationMethodSubmittedEvent> seen = new AtomicReference<>();
        PendingOperation unreadable = row("{ this is not json");
        PendingOperationService service = serviceReturning(OperationResult.completed(), seen);
        when(store.pending(ExternalSystem.OPENPROJECT)).thenReturn(List.of(unreadable));

        service.retryPending(ExternalSystem.OPENPROJECT);

        verify(store).abandon(eq(ROW_ID), contains("Unreadable payload"));
        assertThat(seen.get()).isNull();
    }

    @Test
    void retriesEverythingOneSystemIsStillOwed() throws Exception {
        String payload = objectMapper.writeValueAsString(EVENT);
        PendingOperation first = row(payload);
        PendingOperation second = row(payload);
        second.setId(43L);
        when(store.pending(ExternalSystem.OPENPROJECT)).thenReturn(List.of(first, second));
        when(store.countPending(ExternalSystem.OPENPROJECT)).thenReturn(2L);

        int completed = serviceReturning(OperationResult.completed(), new AtomicReference<>())
                .retryPending(ExternalSystem.OPENPROJECT);

        assertThat(completed).isEqualTo(2);
        InOrder order = inOrder(store);
        order.verify(store).attempted(ROW_ID, OperationOutcome.COMPLETED, null);
        order.verify(store).attempted(43L, OperationOutcome.COMPLETED, null);
    }

    @Test
    void countsOnlyTheOperationsThatSucceeded() throws Exception {
        PendingOperation pending = row(objectMapper.writeValueAsString(EVENT));
        when(store.pending(ExternalSystem.OPENPROJECT)).thenReturn(List.of(pending));

        int completed = serviceReturning(OperationResult.retry("the portal is unreachable"), new AtomicReference<>())
                .retryPending(ExternalSystem.OPENPROJECT);

        assertThat(completed).isZero();
    }

    @Test
    void refusesTwoHandlersForTheSameOperation() {
        RetryableOperationHandler<IntegrationMethodSubmittedEvent> handler = RetryableOperationHandler.of(
                ExternalSystem.OPENPROJECT, OPERATION, IntegrationMethodSubmittedEvent.class,
                payload -> OperationResult.completed());

        // Silently keeping one of them would leave the other unreachable, and the operations it was
        // written for would fail in a way nobody could explain.
        assertThatThrownBy(() -> new PendingOperationService(store, objectMapper, List.of(handler, handler)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENPROJECT/" + OPERATION);
    }
}
