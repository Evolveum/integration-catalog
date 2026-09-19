/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.configuration.CatalogProperties;
import com.evolveum.midpoint.integration.catalog.configuration.OpenProjectProperties;
import com.evolveum.midpoint.integration.catalog.dto.SupportTicketDto;
import com.evolveum.midpoint.integration.catalog.integration.OpenProjectClient;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethod;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodId;
import com.evolveum.midpoint.integration.catalog.repository.IntegrationMethodRepository;
import com.evolveum.midpoint.integration.catalog.service.event.BuildFinishedEvent;
import com.evolveum.midpoint.integration.catalog.service.event.ConnectorAddedToReviewEvent;
import com.evolveum.midpoint.integration.catalog.service.event.IntegrationMethodSubmittedEvent;
import com.evolveum.midpoint.integration.catalog.service.event.TutorialFileAddedEvent;
import com.evolveum.midpoint.integration.catalog.service.retry.OperationResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Keeps a submitted revision paired with a work package in the support portal, where the author and
 * the reviewer discuss the submission. The catalog only opens the work package and reads its status
 * back. The pairing lives on the revision row, so a fork gets its own while an in-place edit keeps one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupportTicketService {

    /** Name the tutorial is attached under, also named by the description. */
    public static final String TUTORIAL_ATTACHMENT = "tutorial.md";

    public static final String OPEN_WORK_PACKAGE = "OPEN_WORK_PACKAGE";

    public static final String APPEND_CONNECTOR = "APPEND_CONNECTOR";

    public static final String ATTACH_FILE = "ATTACH_FILE";

    public static final String COMMENT_BUILD_OUTCOME = "COMMENT_BUILD_OUTCOME";

    public static final String OBJECT_STATE_ADDED = "added";
    public static final String OBJECT_STATE_REPLACED = "replaced";
    public static final String OBJECT_STATE_REMOVED = "removed";

    private final IntegrationMethodRepository integrationMethodRepository;
    private final OpenProjectClient openProjectClient;
    private final OpenProjectProperties properties;
    private final CatalogProperties catalogProperties;
    private final AuthService authService;
    private final SupportTicketDescriptionBuilder descriptionBuilder;
    private final SupportTicketDeltaBuilder deltaBuilder;
    private final TutorialStorageService tutorialStorageService;

    /** A file the submission carries, read and named as it will be attached. */
    private record SubmittedFile(String fileName, byte[] content, String contentType) {
    }

    /**
     * The files of one revision.
     *
     * @param complete whether every file the revision lists could be read, i.e. whether an attachment
     *                 missing from {@code files} really means the author withdrew it
     */
    private record SubmittedFiles(List<SubmittedFile> files, boolean complete) {
    }

    /**
     * Opens a work package for a submitted revision, or rewrites the one it already has. Runs after
     * the submitting transaction commits, in its own transaction, so an unreachable portal costs the
     * author nothing but time: the operation stays pending and the scheduled retry opens the work
     * package once the portal is back. Until then {@link #getStatusOfWorkPackage} reports no ticket, so the
     * approval dialog has nothing to wait for.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OperationResult openWorkPackage(IntegrationMethodSubmittedEvent event) {
        if (!properties.enabled()) {
            log.debug("Support portal is not configured, so {}/{} keeps waiting for its work package",
                    event.methodId(), event.revision());
            return OperationResult.retry("No support portal is configured (openproject.url is empty).");
        }
        IntegrationMethod method = integrationMethodRepository
                .findById(new IntegrationMethodId(event.methodId(), event.revision()))
                .orElse(null);
        if (method == null) {
            log.error("Revision {}/{} no longer exists, so no work package is opened for it",
                    event.methodId(), event.revision());
            return OperationResult.obsolete("Revision " + event.methodId() + "/" + event.revision()
                    + " no longer exists; whatever replaced it carries the work package.");
        }
        if (method.getSupportTicketId() != null) {
            return rewriteWorkPackage(method.getSupportTicketId(), method, event);
        }

        return createWorkPackage(method, event);
    }

    private @NonNull OperationResult createWorkPackage(IntegrationMethod method, IntegrationMethodSubmittedEvent event) {
        try {
            int workPackageId = openProjectClient.createWorkPackage(
                    event.flow().taskName(method.getDisplayName()), descriptionBuilder.build(method));
            method.setSupportTicketId(workPackageId);
            log.info("Opened work package {} for integration method {}/{} in the support portal",
                    workPackageId, event.methodId(), event.revision());
            attachFiles(workPackageId, method);
            addWatchers(workPackageId, method);
            commentOnEditOfPublishedRevision(workPackageId, method, event);
            return OperationResult.completed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while opening a work package for integration method {}/{} in the support portal",
                    event.methodId(), event.revision(), e);
            return OperationResult.retry("Interrupted while opening the work package.");
        } catch (Exception e) {
            log.error("Failed to open a work package for integration method {}/{} in the support portal",
                    event.methodId(), event.revision(), e);
            return OperationResult.retry("Could not open the work package: " + reasonFromException(e));
        }
    }

    /**
     * Rewrites the work package of a revision edited while under review, so the reviewer reads the
     * submission as it stands. A portal that cannot be reached leaves the operation pending for the
     * scheduled retry.
     */
    private OperationResult rewriteWorkPackage(int workPackageId, IntegrationMethod method,
                                               IntegrationMethodSubmittedEvent event) {
        try {
            String titleOfWorkPackage = SubmissionFlow.renamed(
                    openProjectClient.getTitleOfWorkPackage(workPackageId).orElse(null), method.getDisplayName());
            if (titleOfWorkPackage == null) {
                // Nothing to keep - the portal has no subject to build on, so name it by this flow.
                titleOfWorkPackage = event.flow().taskName(method.getDisplayName());
            }
            String description = descriptionBuilder.build(method);
            Optional<String> replaced = openProjectClient.updateWorkPackage(workPackageId, titleOfWorkPackage, description);
            if (replaced.isEmpty()) {
                log.warn("Work package {} of integration method {}/{} no longer exists in the support portal, "
                        + "so the edit was not written to it", workPackageId, event.methodId(), event.revision());
                return OperationResult.obsolete("Work package #" + workPackageId
                        + " no longer exists in the support portal, so the edit could not be written to it.");
            }
            log.info("Updated work package {} after an edit of integration method {}/{} in the support portal",
                    workPackageId, event.methodId(), event.revision());
            List<String> fileChanges = refreshAttachments(workPackageId, method);

            //TODO Do we really not need to handle the exception in the `comment` method? What if the comment doesn’t go through?
            comment(workPackageId, deltaBuilder.compare(replaced.get(), description,
                    "This submission was edited while under review. The description above and the files"
                            + " attached to this work package are up to date; what changed is listed here.",
                    fileChanges));
            return OperationResult.completed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while updating work package {} of integration method {}/{} in the support portal",
                    workPackageId, event.methodId(), event.revision(), e);
            return OperationResult.retry("Interrupted while rewriting work package #" + workPackageId + ".");
        } catch (Exception e) {
            log.error("Failed to update work package {} of integration method {}/{} in the support portal",
                    workPackageId, event.methodId(), event.revision(), e);
            return OperationResult.retry("Could not rewrite work package #" + workPackageId
                    + " after the edit: " + reasonFromException(e));
        }
    }

    /**
     * Comments what a draft forked off a published revision changes about it, so the reviewer reviews
     * an edit rather than the whole method again. Only for {@link SubmissionFlow#EDIT}; best effort,
     * and last, so a failed comment costs nothing already done.
     */
    private void commentOnEditOfPublishedRevision(int workPackageId, IntegrationMethod method,
                                                  IntegrationMethodSubmittedEvent event) {
        if (event.flow() != SubmissionFlow.EDIT || event.previousRevision() == null) {
            return;
        }
        IntegrationMethod previous = integrationMethodRepository
                .findById(new IntegrationMethodId(event.methodId(), event.previousRevision()))
                .orElse(null);
        if (previous == null) {
            log.debug("Revision {}/{} edits {}, which no longer exists, so work package {} gets no"
                            + " summary of the edit",
                    event.methodId(), event.revision(), event.previousRevision(), workPackageId);
            return;
        }
        Optional<String> delta;
        try {
            delta = deltaBuilder.compare(
                    descriptionBuilder.build(previous), descriptionBuilder.build(method),
                    "This is an edit of revision " + event.previousRevision() + ", which it replaces once"
                            + " approved. What it changes about that revision is listed here.",
                    checkTutorialChange(previous, method));
        } catch (Exception e) {
            log.error("Could not work out what {}/{} changes about {}",
                    event.methodId(), event.revision(), event.previousRevision(), e);
            return;
        }
        comment(workPackageId, delta);
    }

    private static List<String> checkTutorialChange(IntegrationMethod before, IntegrationMethod after) {
        String was = blankToNull(before.getTutorial());
        String now = blankToNull(after.getTutorial());
        if (Objects.equals(was, now)) {
            return List.of();
        }
        String what = was == null ? OBJECT_STATE_ADDED : now == null ? OBJECT_STATE_REMOVED : OBJECT_STATE_REPLACED;
        return List.of("`" + TUTORIAL_ATTACHMENT + "` " + what);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String reasonFromException(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + message;
    }

    private void comment(int workPackageId, Optional<String> comment) {
        if (comment.isEmpty()) {
            return;
        }
        try {
            openProjectClient.addComment(workPackageId, comment.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while commenting on work package {} in the support portal", workPackageId, e);
        } catch (Exception e) {
            log.error("Could not comment on work package {} in the support portal", workPackageId, e);
        }
    }

    /**
     * Comments the outcome of a build onto the work package of the revision it was built for, so a
     * reviewer sees the artifact arrive - or sees why it did not - without opening the catalog.
     *
     * @return whether the outcome is now on the work package, and if not, whether asking again
     * could change that
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OperationResult commentBuildOutcome(BuildFinishedEvent event) {
        if (!properties.enabled()) {
            log.debug("Support portal is not configured, so the build outcome of {}/{} keeps waiting",
                    event.methodId(), event.revision());
            return OperationResult.retry("No support portal is configured (openproject.url is empty).");
        }
        IntegrationMethod method = integrationMethodRepository
                .findById(new IntegrationMethodId(event.methodId(), event.revision()))
                .orElse(null);
        if (method == null || method.getSupportTicketId() == null) {
            log.debug("Revision {}/{} has no work package to report its build outcome on",
                    event.methodId(), event.revision());
            return OperationResult.obsolete("Revision " + event.methodId() + "/" + event.revision()
                    + " has no work package; its build state is described by the one that opens it.");
        }

        try {
            openProjectClient.addComment(method.getSupportTicketId(), descriptionBuilder.buildBuildOutcome(event));
            log.info("Reported a {} build on support work package {} of integration method {}/{}",
                    event.succeeded() ? "successful" : "failed", method.getSupportTicketId(),
                    event.methodId(), event.revision());
            return OperationResult.completed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while reporting a build outcome on support work package {}",
                    method.getSupportTicketId(), e);
            return OperationResult.retry("Interrupted while reporting a build outcome on work package #"
                    + method.getSupportTicketId() + ".");
        } catch (Exception e) {
            log.error("Failed to report a build outcome on support work package {}: {}",
                    method.getSupportTicketId(), e.getMessage());
            return OperationResult.retry("Could not report a build outcome on work package #"
                    + method.getSupportTicketId() + ": " + reasonFromException(e));
        }
    }

    /**
     * Comments a connector added to a revision already under review onto that revision's work package,
     * rather than opening a second one. Same shape as {@link #openWorkPackage}: a portal that cannot be
     * reached delays the comment, and the retry composes it from the revision as it stands then.
     *
     * <p>A revision with no work package is given up on rather than retried - the operation that opens
     * it describes the revision as it stands then, this connector included, so retrying would say it
     * twice.
     *
     * @return whether the connector is now on the work package, and if not, whether asking again
     * could change that
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OperationResult appendConnector(ConnectorAddedToReviewEvent event) {
        if (!properties.enabled()) {
            log.debug("Support portal is not configured, so connector {} keeps waiting to be appended",
                    event.connectorId());
            return OperationResult.retry("No support portal is configured (openproject.url is empty).");
        }
        IntegrationMethod method = integrationMethodRepository
                .findById(new IntegrationMethodId(event.methodId(), event.revision()))
                .orElse(null);
        if (method == null || method.getSupportTicketId() == null) {
            log.error("Revision {}/{} has no work package to append connector {} to",
                    event.methodId(), event.revision(), event.connectorId());
            return OperationResult.obsolete("Revision " + event.methodId() + "/" + event.revision()
                    + " has no work package; the connector is described by the one that opens it.");
        }

        try {
            openProjectClient.addComment(method.getSupportTicketId(),
                    descriptionBuilder.buildConnectorAddendum(method, event.connectorId()));
            log.info("Appended connector {} to work package {} of integration method {}/{} in the support portal",
                    event.connectorId(), method.getSupportTicketId(), event.methodId(), event.revision());
            return OperationResult.completed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while appending connector {} to work package {} in the support portal",
                    event.connectorId(), method.getSupportTicketId(), e);
            return OperationResult.retry("Interrupted while appending connector " + event.connectorId()
                    + " to work package #" + method.getSupportTicketId() + ".");
        } catch (Exception e) {
            log.error("Failed to append connector {} to work package {} in the support portal",
                    event.connectorId(), method.getSupportTicketId(), e);
            return OperationResult.retry("Could not append connector " + event.connectorId()
                    + " to work package #" + method.getSupportTicketId() + ": " + reasonFromException(e));
        }
    }

    private void attachFiles(int workPackageId, IntegrationMethod method) {
        List<String> refused = new ArrayList<>();
        for (SubmittedFile file : collectFiles(method, workPackageId).files()) {
            if (addingAttachment(workPackageId, file).isObsolete()) {
                refused.add(file.fileName());
            }
        }
        commentOnRefusedFiles(workPackageId, method, refused);
    }

    private SubmittedFiles collectFiles(IntegrationMethod method, int workPackageId) {
        List<SubmittedFile> files = new ArrayList<>();
        String tutorial = method.getTutorial();
        if (tutorial != null && !tutorial.isBlank()) {
            files.add(new SubmittedFile(TUTORIAL_ATTACHMENT,
                    tutorial.getBytes(StandardCharsets.UTF_8), "text/markdown"));
        }

        List<String> names;
        try {
            names = tutorialStorageService.listTutorialFiles(method.getId(), method.getRevision());
        } catch (Exception e) {
            log.warn("Could not list tutorial files of {}/{} to attach them to work package {}: {}",
                    method.getId(), method.getRevision(), workPackageId, e.getMessage());
            return new SubmittedFiles(files, false);
        }

        boolean complete = true;
        for (String name : names) {
            Optional<SubmittedFile> file = readStoredFile(method, name, workPackageId);
            if (file.isPresent()) {
                files.add(file.get());
            } else {
                complete = false;
            }
        }
        return new SubmittedFiles(files, complete);
    }

    /**
     * Brings a work package's files back in line with the revision after an edit. Only the catalog's
     * own uploads are touched, and only where the content differs; anything a reviewer attached is
     * left alone.
     *
     * @return what changed, for the comment explaining the edit; empty when nothing did
     */
    private List<String> refreshAttachments(int workPackageId, IntegrationMethod method) {
        SubmittedFiles submitted = collectFiles(method, workPackageId);

        Map<String, List<OpenProjectClient.Attachment>> ours;
        try {
            OptionalInt self = openProjectClient.findSelfId();
            if (self.isEmpty()) {
                //TODO note about this situation have to be added to ticket as commit
                // Without knowing which uploads are the catalog's own, every deletion is a guess at
                // somebody else's file and every upload is a duplicate. Better to leave it as it is.
                log.error("Support portal did not say who the catalog signs in as, so the files of work"
                        + " package {} were left as they are", workPackageId);
                return List.of();
            }
            String selfHref = "/" + self.getAsInt();
            ours = openProjectClient.listAttachments(workPackageId).stream()
                    .filter(attachment -> attachment.authorHref().endsWith(selfHref))
                    .collect(Collectors.groupingBy(OpenProjectClient.Attachment::fileName,
                            LinkedHashMap::new, Collectors.toList()));
        } catch (InterruptedException e) {
            //TODO note about this situation have to be added to ticket as commit or scheduled for a repeat broadcast
            Thread.currentThread().interrupt();
            log.error("Interrupted while reading the files of work package {} in the support portal", workPackageId, e);
            return List.of();
        } catch (Exception e) {
            //TODO note about this situation have to be added to ticket as commit or scheduled for a repeat broadcast
            log.error("Could not read the files of work package {} in the support portal, so they were left as they are",
                    workPackageId, e);
            return List.of();
        }

        List<String> changes = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        for (SubmittedFile file : submitted.files()) {
            List<OpenProjectClient.Attachment> present = ours.remove(file.fileName());
            String digest = md5(file.content());
            OpenProjectClient.Attachment same = present == null ? null : present.stream()
                    .filter(attachment -> digest != null && digest.equalsIgnoreCase(attachment.digest()))
                    .findFirst()
                    .orElse(null);

            if (same != null) {
                deleteOthersAttachments(workPackageId, present, same);
                continue;
            }

            OperationResult attached = addingAttachment(workPackageId, file);
            if (!attached.isCompleted()) {
                if (attached.isObsolete()) {
                    refused.add(file.fileName());
                }
                continue;
            }
            boolean replaced = deleteOthersAttachments(workPackageId, present, null);
            changes.add("`" + file.fileName() + "` " + (replaced ? OBJECT_STATE_REPLACED : OBJECT_STATE_ADDED));
        }

        commentOnRefusedFiles(workPackageId, method, refused);

        if (!submitted.complete()) {
            //TODO note about this situation have to be added to ticket as commit or scheduled for a repeat broadcast
            log.error("Not everything {}/{} carries could be read, so nothing was removed from work package {}",
                    method.getId(), method.getRevision(), workPackageId);
            return changes;
        }
        for (List<OpenProjectClient.Attachment> withdrawn : ours.values()) {
            for (OpenProjectClient.Attachment attachment : withdrawn) {
                if (deleteAttachment(workPackageId, attachment)) {
                    changes.add("`" + attachment.fileName() + "` " + OBJECT_STATE_REMOVED);
                }
            }
        }
        return changes;
    }

    private boolean deleteOthersAttachments(int workPackageId, List<OpenProjectClient.Attachment> present,
                                            OpenProjectClient.Attachment keep) {
        if (present == null) {
            return false;
        }
        boolean removed = false;
        for (OpenProjectClient.Attachment attachment : present) {
            if (attachment != keep) {
                removed |= deleteAttachment(workPackageId, attachment);
            }
        }
        return removed;
    }

    private boolean deleteAttachment(int workPackageId, OpenProjectClient.Attachment attachment) {
        try {
            //TODO use return form deleteAttachment -> delete can failed
            openProjectClient.deleteAttachment(attachment.id());
            log.info("Removed {} from support work package {}", attachment.fileName(), workPackageId);
            return true;
        } catch (InterruptedException e) {
            //TODO note about this situation have to be added to ticket as commit or scheduled for a repeat broadcast
            Thread.currentThread().interrupt();
            log.error("Interrupted while removing {} from work package {} in support portal",
                    attachment.fileName(), workPackageId, e);
            return false;
        } catch (Exception e) {
            //TODO note about this situation have to be added to ticket as commit or scheduled for a repeat broadcast
            log.error("Could not remove {} from work package {} in support portal",
                    attachment.fileName(), workPackageId, e);
            return false;
        }
    }

    /** The content's md5, which is the digest the portal reports for what it stores. */
    private static String md5(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content));
        } catch (NoSuchAlgorithmException e) {
            //TODO this error will go unnoticed
            log.error("No md5 available, so attached files cannot be compared", e);
            return null;
        }
    }

    /**
     * Attaches one of the author's uploaded files, which arrive after the create call and so are
     * missing from the description. The portal notes each in the activity itself, so nothing is commented.
     *
     * <p>A revision with no work package is given up on rather than retried, for the reason given on
     * {@link #appendConnector}: the operation that opens one attaches everything the revision carries.
     *
     * @return whether the file is now on the work package, and if not, whether asking again could
     * change that
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OperationResult attachTutorialFile(TutorialFileAddedEvent event) {
        if (!properties.enabled()) {
            log.debug("Support portal is not configured, so {} keeps waiting to be attached",
                    event.fileName());
            return OperationResult.retry("No support portal is configured (openproject.url is empty).");
        }
        IntegrationMethod method = integrationMethodRepository
                .findById(new IntegrationMethodId(event.methodId(), event.revision()))
                .orElse(null);
        if (method == null || method.getSupportTicketId() == null) {
            log.error("Revision {}/{} has no work package to attach {} to",
                    event.methodId(), event.revision(), event.fileName());
            return OperationResult.obsolete("Revision " + event.methodId() + "/" + event.revision()
                    + " has no work package; the file is attached by the operation that opens it.");
        }
        return attachStoredFile(method.getSupportTicketId(), method, event.fileName());
    }

    private OperationResult attachStoredFile(int workPackageId, IntegrationMethod method, String fileName) {
        Optional<SubmittedFile> file = readStoredFile(method, fileName, workPackageId);
        if (file.isEmpty()) {
            String message = "File " + fileName + " of " + method.getId() + "/"
                    + method.getRevision() + " could not be read from the catalog's storage.";
            log.error(message);
            return OperationResult.obsolete(message);
        }
        OperationResult attached = addingAttachment(workPackageId, file.get());
        if (attached.isObsolete()) {
            commentOnRefusedFiles(workPackageId, method, List.of(fileName));
        }
        return attached;
    }

    /** One of the author's uploads, read off the disk, or empty when it cannot be read. */
    private Optional<SubmittedFile> readStoredFile(IntegrationMethod method, String fileName, int workPackageId) {
        try {
            Path file = tutorialStorageService.resolveTutorialFile(method.getId(), method.getRevision(), fileName);
            byte[] content = Files.readAllBytes(file);
            String probed = Files.probeContentType(file);
            return Optional.of(new SubmittedFile(fileName, content,
                    probed != null ? probed : "application/octet-stream"));
        } catch (Exception e) {
            log.error("Could not read file {} of {}/{} to attach it to work package {}",
                    fileName, method.getId(), method.getRevision(), workPackageId, e);
            return Optional.empty();
        }
    }

    private OperationResult addingAttachment(int workPackageId, SubmittedFile file) {
        try {
            openProjectClient.addAttachment(workPackageId, file.fileName(), file.content(), file.contentType());
            log.info("Attached {} ({} bytes) to support work package {}",
                    file.fileName(), file.content().length, workPackageId);
            return OperationResult.completed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while attaching {} to work package {} in the support portal", file.fileName(), workPackageId, e);
            return OperationResult.retry("Interrupted while attaching " + file.fileName()
                    + " to work package #" + workPackageId + ".");
        } catch (OpenProjectClient.AttachmentTooLargeException e) {
            log.error("Work package {} would not take {} ({} bytes) in the support portal: it is over the portal's"
                            + " attachment size limit, so it stays in the catalog only",
                    workPackageId, file.fileName(), file.content().length, e);
            return OperationResult.obsolete("Work package #" + workPackageId + " would not take "
                    + file.fileName() + " (" + file.content().length
                    + " bytes): it is over the support portal's attachment size limit.");
        } catch (Exception e) {
            log.error("Failed to attach {} to work package {} in the support portal",
                    file.fileName(), workPackageId, e);
            return OperationResult.retry("Could not attach " + file.fileName() + " to work package #"
                    + workPackageId + ": " + reasonFromException(e));
        }
    }

    /**
     * Tells the reviewer, on the work package itself, about files the portal would not take and where
     * to get them instead - otherwise the Files tab is simply short and nothing says why. One comment
     * per run, however many files it refused, so several oversized samples do not bury the review.
     */
    private void commentOnRefusedFiles(int workPackageId, IntegrationMethod method, List<String> refused) {
        if (refused.isEmpty()) {
            return;
        }
        boolean one = refused.size() == 1;
        StringBuilder body = new StringBuilder("**")
                .append(one ? "A file of this submission could not be attached"
                        : "Some files of this submission could not be attached")
                .append("**\n\n")
                .append("The support portal would not accept ")
                .append(one ? "this file, which is" : "these files, which are")
                .append(" over its attachment size limit:\n\n");
        for (String fileName : refused) {
            body.append("- `").append(fileName).append("`\n");
        }
        body.append("\nNothing is missing from the submission itself. ")
                .append(one ? "The file is" : "The files are")
                .append(" stored in the integration catalog and can be downloaded from the revision's page");

        String url = catalogProperties.integrationMethodUrl(
                method.getApplication() != null ? method.getApplication().getId() : null,
                method.getId(), method.getRevision());
        body.append(url != null ? ":\n\n" + url + "\n" : " in the catalog.\n");

        comment(workPackageId, Optional.of(body.toString()));
    }

    private void addWatchers(int workPackageId, IntegrationMethod method) {
        Set<Integer> watching = new LinkedHashSet<>();

        for (String login : properties.watchers()) {
            OptionalInt userId = resolveUserIdOfOpUser(login, () -> openProjectClient.findUserIdByLogin(login), workPackageId);
            addWatcher(workPackageId, login, userId, watching);
        }

        for (String name : checkNullAndDistinctNames(method.getAuthor().getUsername(), method.getMaintainer().getUsername())) {
            OptionalInt userId = attempt(name, () -> openProjectClient.findUserIdByLogin(name));
            String who = name;
            if (userId.isEmpty()) {
                // No account under that login: fall back to the address the row carries, which the
                // author has and nobody else does.
                String email = method.getAuthor().getEmail();
                if (email == null) {
                    log.debug("No portal login '{}' and no contact address for them, "
                            + "not watching work package {}", name, workPackageId);
                    continue;
                }
                who = email;
            }
            addWatcher(workPackageId, who, userId, watching);
        }
    }

    private void addWatcher(int workPackageId, String who, OptionalInt userId, Set<Integer> watching) {
        if (userId.isEmpty() || !watching.add(userId.getAsInt())) {
            return;
        }
        try {
            openProjectClient.addWatcher(workPackageId, userId.getAsInt());
            log.debug("Added '{}' as a watcher of support work package {}", who, workPackageId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while adding watchers to support work package {}", workPackageId, e);
        } catch (Exception e) {
            log.error("Could not add '{}' as a watcher of support work package {}",
                    who, workPackageId, e);
        }
    }

    private OptionalInt findOpUserByEmail(String email, int workPackageId) {
        return resolveUserIdOfOpUser(email, () -> openProjectClient.findUserIdByEmail(email), workPackageId);
    }

    private OptionalInt resolveUserIdOfOpUser(String who, PortalLookup lookup, int workPackageId) {
        OptionalInt userId = attempt(who, lookup);
        if (userId.isEmpty()) {
            log.info("Support portal has no user for '{}', not watching work package {}",
                    who, workPackageId);
        }
        return userId;
    }

    /**
     * One portal lookup without reporting a miss, for a caller that has another way left to try -
     * see {@link #addWatchers}, where a login that is not a portal account is an ordinary step on the
     * way to the address rather than something to warn about. A failed query is still logged.
     */
    private OptionalInt attempt(String who, PortalLookup lookup) {
        try {
            return lookup.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while looking '{}' up in the support portal", who, e);
            return OptionalInt.empty();
        } catch (Exception e) {
            log.error("Could not look '{}' up in the support portal", who, e);
            return OptionalInt.empty();
        }
    }

    /** One lookup on {@link OpenProjectClient}, so the error handling around it is written once. */
    @FunctionalInterface
    private interface PortalLookup {
        OptionalInt get() throws IOException, InterruptedException;
    }

    private static List<String> checkNullAndDistinctNames(String... names) {
        return Stream.of(names)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
    }

    /**
     * The work package behind a revision's review and whether it lets the reviewer approve. Restricted
     * to the reviewer and the submitting side by the same ownership check that guards editing. Never
     * throws for a portal problem - that is reported through {@link SupportTicketDto#error()}.
     */
    // Deliberately not @Transactional: the portal call below can take seconds, and there is no
    // reason to hold a database connection open across it. The one read stands on its own.
    public SupportTicketDto getStatusOfWorkPackage(UUID methodId, String revision, String username) {
        IntegrationMethod method = integrationMethodRepository
                .findById(new IntegrationMethodId(methodId, revision))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Integration method not found: " + methodId + "/" + revision));

        // canEdit already lets a superuser through, so the reviewer needs no separate case. The
        // organization ids go with the names: an item maintained by an organization carries no
        // maintainer username, so a name-only check would lock out the org-mates the review concerns.
        if (!authService.canEdit(username, method.getLifecycleState(), method.getAuthor(), method.getMaintainer())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Not allowed to see the support ticket of " + methodId + "/" + revision);
        }

        if (!properties.enabled()) {
            return new SupportTicketDto(false, null, null, null, true, null);
        }

        Integer ticketId = method.getSupportTicketId();
        if (ticketId == null) {
            return new SupportTicketDto(true, null, null, null, true, null);
        }

        String url = properties.workPackageUrl(ticketId);
        try {
            Optional<OpenProjectClient.WorkPackageStatus> status = openProjectClient.readStatus(ticketId);
            if (status.isEmpty() || status.get().closed() == null) {
                return new SupportTicketDto(true, ticketId, url, null, false,
                        "Work package #" + ticketId + " no longer exists in the support portal.");
            }
            return new SupportTicketDto(true, ticketId, url, status.get().name(), status.get().closed(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SupportTicketDto(true, ticketId, url, null, false,
                    "Interrupted while reading the support portal.");
        } catch (Exception e) {
            log.warn("Could not read support work package {} for {}/{}: {}",
                    ticketId, methodId, revision, e.getMessage());
            return new SupportTicketDto(true, ticketId, url, null, false,
                    "The support portal could not be reached.");
        }
    }

}
