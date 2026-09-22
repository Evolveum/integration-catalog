/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.configuration.CatalogProperties;
import com.evolveum.midpoint.integration.catalog.configuration.OpenProjectProperties;
import com.evolveum.midpoint.integration.catalog.integration.OpenProjectClient;
import com.evolveum.midpoint.integration.catalog.object.Author;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethod;
import com.evolveum.midpoint.integration.catalog.object.Maintainer;
import com.evolveum.midpoint.integration.catalog.object.MaintainerType;
import com.evolveum.midpoint.integration.catalog.repository.IntegrationMethodRepository;
import com.evolveum.midpoint.integration.catalog.service.event.BuildFinishedEvent;
import com.evolveum.midpoint.integration.catalog.service.event.ConnectorAddedToReviewEvent;
import com.evolveum.midpoint.integration.catalog.service.event.IntegrationMethodSubmittedEvent;
import com.evolveum.midpoint.integration.catalog.service.event.TutorialFileAddedEvent;
import com.evolveum.midpoint.integration.catalog.object.ExternalSystem;
import com.evolveum.midpoint.integration.catalog.service.retry.OperationOutcome;
import com.evolveum.midpoint.integration.catalog.service.retry.OperationResult;
import com.evolveum.midpoint.integration.catalog.service.retry.PendingOperationStore;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the four support-portal handlers do, each checked on the three answers it can give the retry
 * queue: done, ask again later, and never worth asking again.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupportTicketHandlersTest {

    private static final UUID METHOD_ID = UUID.randomUUID();
    private static final String REVISION = "1.0";
    private static final int WORK_PACKAGE = 4242;

    @Mock private IntegrationMethodRepository integrationMethodRepository;
    @Mock private OpenProjectClient openProjectClient;
    @Mock private SupportTicketDescriptionBuilder descriptionBuilder;
    @Mock private SupportTicketDeltaBuilder deltaBuilder;
    @Mock private TutorialStorageService tutorialStorageService;
    @Mock private PendingOperationStore pendingOperationStore;

    private OpenWorkPackageHandler open;
    private CommentBuildOutcomeHandler buildOutcome;
    private AppendConnectorHandler appendConnector;
    private AttachTutorialFileHandler attachFile;
    private PostCommentHandler postComment;
    private SupportTicketComments comments;

    @TempDir
    Path storage;

    @BeforeEach
    void setUp() {
        handlersFor(configuredPortal());
        when(descriptionBuilder.build(any())).thenReturn("the description");
        when(deltaBuilder.compare(any(), any(), any(), any())).thenReturn(Optional.empty());
    }

    /**
     * The collaborators are real, with only the portal and the storage mocked, so these cover the
     * attaching and watching a handler delegates rather than stubbing it away.
     */
    private void handlersFor(OpenProjectProperties properties) {
        comments = new SupportTicketComments(openProjectClient, pendingOperationStore, new ObjectMapper());
        SupportTicketAttachments attachments = new SupportTicketAttachments(openProjectClient,
                new CatalogProperties("http://catalog"), tutorialStorageService, comments);
        SupportTicketWatchers watchers = new SupportTicketWatchers(openProjectClient, properties);
        postComment = new PostCommentHandler(openProjectClient);

        open = new OpenWorkPackageHandler(integrationMethodRepository, openProjectClient, properties,
                descriptionBuilder, deltaBuilder, comments, attachments, watchers);
        buildOutcome = new CommentBuildOutcomeHandler(integrationMethodRepository, openProjectClient,
                properties, descriptionBuilder);
        appendConnector = new AppendConnectorHandler(integrationMethodRepository, openProjectClient,
                properties, descriptionBuilder);
        attachFile = new AttachTutorialFileHandler(integrationMethodRepository, properties, attachments);
    }

    private static OpenProjectProperties configuredPortal() {
        return new OpenProjectProperties("http://portal", "key", "project", 1, List.of(), false, List.of());
    }

    /** No URL is how a deployment says it has no support portal. */
    private static OpenProjectProperties noPortal() {
        return new OpenProjectProperties(null, null, null, 1, List.of(), false, List.of());
    }

    private IntegrationMethod method(Integer workPackageId) {
        IntegrationMethod method = new IntegrationMethod();
        method.setId(METHOD_ID);
        method.setRevision(REVISION);
        method.setDisplayName("LDAP Connector");
        method.setSupportTicketId(workPackageId);
        method.setAuthor(new Author().setUsername("author").setEmail("author@example.org"));
        method.setMaintainer(new Maintainer().setCategory(MaintainerType.USER).setUsername("owner"));
        return method;
    }

    private void repositoryHolds(IntegrationMethod method) {
        when(integrationMethodRepository.findById(any())).thenReturn(Optional.ofNullable(method));
    }

    private static IntegrationMethodSubmittedEvent submitted() {
        return new IntegrationMethodSubmittedEvent(METHOD_ID, REVISION, SubmissionFlow.CREATE, null);
    }

    // ── opening the work package ────────────────────────────────────────────

    @Test
    void openingIsPostponedWhileNoPortalIsConfigured() throws Exception {
        handlersFor(noPortal());

        OperationResult result = open.execute(submitted());

        assertEquals(OperationOutcome.RETRY, result.outcome());
        verifyNoWorkPackageWasOpened();
    }

    /** The revision was replaced in the meantime; whatever replaced it carries the work package. */
    @Test
    void openingIsGivenUpOnWhenTheRevisionIsGone() throws Exception {
        repositoryHolds(null);

        OperationResult result = open.execute(submitted());

        assertTrue(result.isObsolete());
        verifyNoWorkPackageWasOpened();
    }

    @Test
    void openingRecordsTheWorkPackageOnTheRevision() throws Exception {
        IntegrationMethod method = method(null);
        repositoryHolds(method);
        when(openProjectClient.createWorkPackage(anyString(), anyString())).thenReturn(WORK_PACKAGE);

        OperationResult result = open.execute(submitted());

        assertTrue(result.isCompleted());
        assertEquals(WORK_PACKAGE, method.getSupportTicketId().intValue());
        verify(openProjectClient).createWorkPackage(SubmissionFlow.CREATE.taskName("LDAP Connector"),
                "the description");
    }

    @Test
    void openingIsRetriedWhenThePortalRefuses() throws Exception {
        repositoryHolds(method(null));
        when(openProjectClient.createWorkPackage(anyString(), anyString()))
                .thenThrow(new IOException("portal down"));

        OperationResult result = open.execute(submitted());

        assertEquals(OperationOutcome.RETRY, result.outcome());
        assertTrue(result.detail().contains("portal down"));
    }

    /** A revision that already has one is edited under review, so the work package is rewritten. */
    @Test
    void aRevisionThatAlreadyHasOneHasItRewrittenInsteadOfOpeningASecond() throws Exception {
        repositoryHolds(method(WORK_PACKAGE));
        when(openProjectClient.getTitleOfWorkPackage(WORK_PACKAGE)).thenReturn(Optional.of("Create new IM: LDAP"));
        when(openProjectClient.updateWorkPackage(anyInt(), anyString(), anyString()))
                .thenReturn(Optional.of("the previous description"));
        when(openProjectClient.listAttachments(anyInt())).thenReturn(List.of());

        OperationResult result = open.execute(submitted());

        assertTrue(result.isCompleted());
        verify(openProjectClient).updateWorkPackage(eq(WORK_PACKAGE), anyString(), eq("the description"));
        verify(openProjectClient, never()).createWorkPackage(anyString(), anyString());
    }

    /** The portal forgot the work package, so there is nothing left to rewrite. */
    @Test
    void rewritingIsGivenUpOnWhenThePortalNoLongerHasTheWorkPackage() throws Exception {
        repositoryHolds(method(WORK_PACKAGE));
        when(openProjectClient.getTitleOfWorkPackage(WORK_PACKAGE)).thenReturn(Optional.of("Create new IM: LDAP"));
        when(openProjectClient.updateWorkPackage(anyInt(), anyString(), anyString())).thenReturn(Optional.empty());

        OperationResult result = open.execute(submitted());

        assertTrue(result.isObsolete());
    }

    // ── the build outcome ───────────────────────────────────────────────────

    @Test
    void aBuildOutcomeIsCommentedOnTheWorkPackage() throws Exception {
        repositoryHolds(method(WORK_PACKAGE));
        when(descriptionBuilder.buildBuildOutcome(any())).thenReturn("the build went well");

        OperationResult result = buildOutcome.execute(buildFinished());

        assertTrue(result.isCompleted());
        verify(openProjectClient).addComment(WORK_PACKAGE, "the build went well");
    }

    /** Nothing to comment on: the operation that opens one describes the build state itself. */
    @Test
    void aBuildOutcomeIsGivenUpOnWhenTheRevisionHasNoWorkPackage() {
        repositoryHolds(method(null));

        OperationResult result = buildOutcome.execute(buildFinished());

        assertTrue(result.isObsolete());
    }

    @Test
    void aBuildOutcomeIsRetriedWhenThePortalRefuses() throws Exception {
        repositoryHolds(method(WORK_PACKAGE));
        when(descriptionBuilder.buildBuildOutcome(any())).thenReturn("the build went well");
        doThrow(new IOException("portal down")).when(openProjectClient).addComment(anyInt(), anyString());

        OperationResult result = buildOutcome.execute(buildFinished());

        assertEquals(OperationOutcome.RETRY, result.outcome());
        assertTrue(result.detail().contains("portal down"));
    }

    private static BuildFinishedEvent buildFinished() {
        return BuildFinishedEvent.succeeded(METHOD_ID, REVISION, "bundle", "1.0.0",
                "http://nexus/artifact.jar", List.of("com.example.LdapConnector"));
    }

    // ── appending a connector ───────────────────────────────────────────────

    @Test
    void anAddedConnectorIsCommentedOnTheWorkPackage() throws Exception {
        repositoryHolds(method(WORK_PACKAGE));
        when(descriptionBuilder.buildConnectorAddendum(any(), any())).thenReturn("a connector was added");

        OperationResult result = appendConnector.execute(new ConnectorAddedToReviewEvent(METHOD_ID, REVISION, 17));

        assertTrue(result.isCompleted());
        verify(openProjectClient).addComment(WORK_PACKAGE, "a connector was added");
    }

    /** Retrying would say it twice: the operation that opens the work package describes it too. */
    @Test
    void anAddedConnectorIsGivenUpOnWhenTheRevisionHasNoWorkPackage() {
        repositoryHolds(method(null));

        OperationResult result = appendConnector.execute(new ConnectorAddedToReviewEvent(METHOD_ID, REVISION, 17));

        assertTrue(result.isObsolete());
    }

    @Test
    void anAddedConnectorIsRetriedWhenThePortalRefuses() throws Exception {
        repositoryHolds(method(WORK_PACKAGE));
        when(descriptionBuilder.buildConnectorAddendum(any(), any())).thenReturn("a connector was added");
        doThrow(new IOException("portal down")).when(openProjectClient).addComment(anyInt(), anyString());

        OperationResult result = appendConnector.execute(new ConnectorAddedToReviewEvent(METHOD_ID, REVISION, 17));

        assertEquals(OperationOutcome.RETRY, result.outcome());
    }

    // ── attaching an uploaded file ──────────────────────────────────────────

    @Test
    void anUploadedFileIsAttachedToTheWorkPackage() throws Exception {
        repositoryHolds(method(WORK_PACKAGE));
        Path file = storage.resolve("install.pdf");
        Files.write(file, "content".getBytes(StandardCharsets.UTF_8));
        when(tutorialStorageService.resolveTutorialFile(METHOD_ID, REVISION, "install.pdf")).thenReturn(file);

        OperationResult result =
                attachFile.execute(new TutorialFileAddedEvent(METHOD_ID, REVISION, "install.pdf"));

        assertTrue(result.isCompleted());
        verify(openProjectClient).addAttachment(eq(WORK_PACKAGE), eq("install.pdf"), any(), anyString());
    }

    /** The file is gone from the catalog's own storage, so no retry will ever find it. */
    @Test
    void anUploadedFileIsGivenUpOnWhenItCannotBeRead() {
        repositoryHolds(method(WORK_PACKAGE));
        when(tutorialStorageService.resolveTutorialFile(METHOD_ID, REVISION, "install.pdf"))
                .thenReturn(storage.resolve("never-written.pdf"));

        OperationResult result =
                attachFile.execute(new TutorialFileAddedEvent(METHOD_ID, REVISION, "install.pdf"));

        assertTrue(result.isObsolete());
    }

    @Test
    void anUploadedFileIsGivenUpOnWhenTheRevisionHasNoWorkPackage() {
        repositoryHolds(method(null));

        OperationResult result =
                attachFile.execute(new TutorialFileAddedEvent(METHOD_ID, REVISION, "install.pdf"));

        assertTrue(result.isObsolete());
    }

    @Test
    void attachingIsPostponedWhileNoPortalIsConfigured() {
        handlersFor(noPortal());

        OperationResult result =
                attachFile.execute(new TutorialFileAddedEvent(METHOD_ID, REVISION, "install.pdf"));

        assertEquals(OperationOutcome.RETRY, result.outcome());
    }

    // ── comments that outlive an unreachable portal ─────────────────────────

    @Test
    void aCommentThePortalTakesIsNotWrittenDown() throws Exception {
        comments.post(WORK_PACKAGE, Optional.of("all is well"));

        verify(openProjectClient).addComment(WORK_PACKAGE, "all is well");
        verify(pendingOperationStore, never()).record(any(), anyString(), anyString());
    }

    /** The summary cannot be recomposed later, so a portal that refuses it is owed it. */
    @Test
    void aCommentThePortalRefusesIsWrittenDownToBeSaidLater() throws Exception {
        doThrow(new IOException("portal down")).when(openProjectClient).addComment(anyInt(), anyString());

        comments.post(WORK_PACKAGE, Optional.of("what changed"));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(pendingOperationStore).record(eq(ExternalSystem.OPENPROJECT),
                eq(PostCommentHandler.OPERATION), payload.capture());
        assertTrue(payload.getValue().contains("what changed"));
        assertTrue(payload.getValue().contains(String.valueOf(WORK_PACKAGE)));
    }

    @Test
    void nothingIsSaidAndNothingIsOwedWhenThereIsNoComment() throws Exception {
        comments.post(WORK_PACKAGE, Optional.empty());

        verify(openProjectClient, never()).addComment(anyInt(), anyString());
        verify(pendingOperationStore, never()).record(any(), anyString(), anyString());
    }

    @Test
    void anOwedCommentIsSaidWhenItIsAttemptedAgain() throws Exception {
        OperationResult result = postComment.execute(
                new PostCommentHandler.Comment(WORK_PACKAGE, "what changed"));

        assertTrue(result.isCompleted());
        verify(openProjectClient).addComment(WORK_PACKAGE, "what changed");
    }

    @Test
    void anOwedCommentStaysOwedWhileThePortalRefusesIt() throws Exception {
        doThrow(new IOException("portal down")).when(openProjectClient).addComment(anyInt(), anyString());

        OperationResult result = postComment.execute(
                new PostCommentHandler.Comment(WORK_PACKAGE, "what changed"));

        assertEquals(OperationOutcome.RETRY, result.outcome());
        assertTrue(result.detail().contains("portal down"));
    }

    private void verifyNoWorkPackageWasOpened() throws Exception {
        verify(openProjectClient, never()).createWorkPackage(anyString(), anyString());
    }
}
