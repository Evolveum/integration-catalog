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
import com.evolveum.midpoint.integration.catalog.service.retry.OperationResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * Keeps a submitted revision paired with a work package in the support portal, which is where the
 * author and the reviewer discuss the submission. The catalog only opens the work package and
 * reads its status back; everything said about the submission is said in the portal.
 *
 * <p>The pairing lives on the revision row, so which revisions share a ticket follows from how the
 * edit flows treat rows: a draft revised or resubmitted in place keeps its work package, while a
 * draft forked off a published revision is a new submission and gets its own.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupportTicketService {

    /**
     * Name the tutorial is attached under. Referenced by the description
     * ({@link SupportTicketDescriptionBuilder}), so a reviewer told what to look for finds exactly that.
     */
    public static final String TUTORIAL_ATTACHMENT = "tutorial.md";

    public static final String OPEN_WORK_PACKAGE = "OPEN_WORK_PACKAGE";

    public static final String APPEND_CONNECTOR = "APPEND_CONNECTOR";

    public static final String ATTACH_FILE = "ATTACH_FILE";

    private final IntegrationMethodRepository integrationMethodRepository;
    private final OpenProjectClient openProjectClient;
    private final OpenProjectProperties properties;
    private final CatalogProperties catalogProperties;
    private final AuthService authService;
    private final SupportTicketDescriptionBuilder descriptionBuilder;
    private final SupportTicketDeltaBuilder deltaBuilder;
    private final CatalogContactResolver contactResolver;
    private final TutorialStorageService tutorialStorageService;

    /**
     * Opens a work package for a submitted revision, or rewrites the one it already has.
     *
     * <p>Runs in a transaction of its own, after the submitting transaction has committed: the
     * submission is already durable by then, so nothing the portal does can roll it back. What the
     * portal being down costs is only time - the operation was written down before this was
     * attempted, so a failure here leaves it pending and the scheduled retry opens the work package
     * when the portal is back. Until then {@link #describe} reports no ticket and the approval
     * dialog has nothing to wait for, exactly as for a revision submitted before the integration
     * existed.
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
            log.debug("Revision {}/{} no longer exists, so no work package is opened for it",
                    event.methodId(), event.revision());
            return OperationResult.obsolete("Revision " + event.methodId() + "/" + event.revision()
                    + " no longer exists; whatever replaced it carries the work package.");
        }
        if (method.getSupportTicketId() != null) {
            return rewriteWorkPackage(method.getSupportTicketId(), method, event);
        }

        try {
            int workPackageId = openProjectClient.createWorkPackage(
                    event.flow().taskName(method.getDisplayName()), descriptionBuilder.build(method));
            method.setSupportTicketId(workPackageId);
            log.info("Opened support work package {} for integration method {}/{}",
                    workPackageId, event.methodId(), event.revision());
            attachFiles(workPackageId, method);
            addWatchers(workPackageId, method);
            commentOnEditOfPublishedRevision(workPackageId, method, event);
            return OperationResult.completed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while opening a support work package for integration method {}/{}",
                    event.methodId(), event.revision(), e);
            return OperationResult.retry("Interrupted while opening the work package.");
        } catch (Exception e) {
            log.error("Failed to open a support work package for integration method {}/{}: {}",
                    event.methodId(), event.revision(), e.getMessage());
            return OperationResult.retry("Could not open the work package: " + reason(e));
        }
    }

    /**
     * Rewrites the work package of a revision that was edited while it was under review, so what the
     * reviewer reads is the submission as it stands rather than as it was first sent.
     *
     * <p>Edits reach here because an in-place edit replaces the revision row and carries its work
     * package to the replacement, which then raises a submitted event like any other; the ticket id
     * on the row is what tells the two apart.
     */
    private OperationResult rewriteWorkPackage(int workPackageId, IntegrationMethod method,
                                               IntegrationMethodSubmittedEvent event) {
        try {
            String subject = SubmissionFlow.renamed(
                    openProjectClient.readSubject(workPackageId).orElse(null), method.getDisplayName());
            if (subject == null) {
                // Nothing to keep - the portal has no subject to build on, so name it by this flow.
                subject = event.flow().taskName(method.getDisplayName());
            }
            String description = descriptionBuilder.build(method);
            Optional<String> replaced = openProjectClient.updateWorkPackage(workPackageId, subject, description);
            if (replaced.isEmpty()) {
                log.warn("Support work package {} of integration method {}/{} no longer exists, "
                        + "so the edit was not written to it", workPackageId, event.methodId(), event.revision());
                return OperationResult.obsolete("Work package #" + workPackageId
                        + " no longer exists in the support portal, so the edit could not be written to it.");
            }
            log.info("Updated support work package {} after an edit of integration method {}/{}",
                    workPackageId, event.methodId(), event.revision());
            List<String> fileChanges = refreshAttachments(workPackageId, method);
            comment(workPackageId, deltaBuilder.compare(replaced.get(), description,
                    "This submission was edited while under review. The description above and the files"
                            + " attached to this work package are up to date; what changed is listed here.",
                    fileChanges));
            return OperationResult.completed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while updating support work package {} of integration method {}/{}",
                    workPackageId, event.methodId(), event.revision(), e);
            return OperationResult.retry("Interrupted while rewriting work package #" + workPackageId + ".");
        } catch (Exception e) {
            log.error("Failed to update support work package {} of integration method {}/{}: {}",
                    workPackageId, event.methodId(), event.revision(), e.getMessage());
            return OperationResult.retry("Could not rewrite work package #" + workPackageId
                    + " after the edit: " + reason(e));
        }
    }

    /**
     * Says what a draft forked off a published revision changes about it, on the freshly opened work
     * package of that draft.
     *
     * <p>"Save" on a published revision produces a draft that is nearly all inherited: its work
     * package describes a whole submission, most of which the reviewer approved once already, and
     * nothing in it points at the handful of fields the author actually touched. Listing those is the
     * difference between reviewing an edit and reviewing the method again.
     *
     * <p>Only for that flow. A first submission has nothing to differ from, and an upgrade
     * ("Save as new version") stands beside the published revision rather than replacing it, so it is
     * read as a version in its own right.
     *
     * <p>Best effort and deliberately last: the work package is open, described and watched by the
     * time this runs, and a comment that could not be composed is not worth losing that over.
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
                    tutorialChange(previous, method));
        } catch (Exception e) {
            // Describing a revision reads it from the database; failing to describe the older one says
            // nothing about this submission, whose own work package is already open and complete.
            log.warn("Could not work out what {}/{} changes about {}: {}",
                    event.methodId(), event.revision(), event.previousRevision(), e.getMessage());
            return;
        }
        comment(workPackageId, delta);
    }

    /**
     * Whether the tutorial differs between two revisions, said the way {@link #refreshAttachments}
     * says it.
     *
     * <p>Its own line because the description carries no tutorial - it points at the attachment - so
     * two descriptions can be identical while the text the reviewer is asked to read is not. The
     * uploaded samples need no such line: the description names them, so they differ in it already.
     */
    private static List<String> tutorialChange(IntegrationMethod before, IntegrationMethod after) {
        String was = blankToNull(before.getTutorial());
        String now = blankToNull(after.getTutorial());
        if (Objects.equals(was, now)) {
            return List.of();
        }
        String what = was == null ? "added" : now == null ? "removed" : "rewritten";
        return List.of("`" + TUTORIAL_ATTACHMENT + "` " + what);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * A failure in one line, for the reason stored on the pending row.
     *
     * <p>The type is named alongside the message because the messages that matter most here carry
     * the least: a portal that is not running fails with {@code ConnectException: Connection
     * refused}, where the message alone would not say what was refused, and a timeout often has no
     * message at all.
     */
    private static String reason(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + message;
    }

    /**
     * Posts a comment that only exists to make a review easier, so nothing about the submission
     * depends on it arriving. An absent comment is one the caller found nothing to say in.
     */
    private void comment(int workPackageId, Optional<String> comment) {
        if (comment.isEmpty()) {
            return;
        }
        try {
            openProjectClient.addComment(workPackageId, comment.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while commenting on support work package {}", workPackageId);
        } catch (Exception e) {
            log.warn("Could not comment on support work package {}: {}", workPackageId, e.getMessage());
        }
    }

    /**
     * Appends a connector added to a revision that is already under review to that revision's existing
     * work package, so the reviewer sees the submission grow instead of finding a second work package
     * about the same review.
     *
     * <p>Same shape as {@link #openWorkPackage}: after the commit, in its own transaction, and a
     * portal that cannot be reached delays the comment rather than losing it - the scheduled retry
     * posts it later, composed from the revision as it stands then.
     *
     * <p>A revision whose work package has not been opened has nothing to append to, and this is
     * then given up on rather than retried. That is not a hole: if the work package is merely late,
     * the operation that opens it describes the revision at the moment it runs, and the connector
     * added here is part of that description. Retrying as well would put the same connector in front
     * of the reviewer twice.
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
            log.debug("Revision {}/{} has no work package to append connector {} to",
                    event.methodId(), event.revision(), event.connectorId());
            return OperationResult.obsolete("Revision " + event.methodId() + "/" + event.revision()
                    + " has no work package; the connector is described by the one that opens it.");
        }

        try {
            openProjectClient.addComment(method.getSupportTicketId(),
                    descriptionBuilder.buildConnectorAddendum(method, event.connectorId()));
            log.info("Appended connector {} to support work package {} of integration method {}/{}",
                    event.connectorId(), method.getSupportTicketId(), event.methodId(), event.revision());
            return OperationResult.completed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while appending connector {} to support work package {}",
                    event.connectorId(), method.getSupportTicketId(), e);
            return OperationResult.retry("Interrupted while appending connector " + event.connectorId()
                    + " to work package #" + method.getSupportTicketId() + ".");
        } catch (Exception e) {
            log.error("Failed to append connector {} to support work package {}: {}",
                    event.connectorId(), method.getSupportTicketId(), e.getMessage());
            return OperationResult.retry("Could not append connector " + event.connectorId()
                    + " to work package #" + method.getSupportTicketId() + ": " + reason(e));
        }
    }

    /**
     * Puts everything the submission carries as a file onto the work package's Files tab: the tutorial,
     * written out of {@code integration_method.tutorial} as {@value #TUTORIAL_ATTACHMENT}, and every
     * sample the author has uploaded so far.
     *
     * <p>Attachments rather than description text, because a tutorial is a {@code text} column with no
     * length limit and a sample is not text at all. Both would either bury every other field in the
     * ticket or, past the portal's limits, cost the whole work package.
     *
     * <p>"So far" matters: on a first submission the folder is still empty here, because the publish form
     * uploads the samples only after the create call returns. Those arrive later through
     * {@link #attachTutorialFile}. An edited or upgraded revision, on the other hand, already carries
     * its predecessor's files, and they are attached here.
     */
    private void attachFiles(int workPackageId, IntegrationMethod method) {
        List<String> refused = new ArrayList<>();
        for (SubmittedFile file : collectFiles(method, workPackageId).files()) {
            if (attach(workPackageId, file).isObsolete()) {
                refused.add(file.fileName());
            }
        }
        commentOnRefusedFiles(workPackageId, method, refused);
    }

    /**
     * Everything the revision carries as a file, read and ready to be attached: the tutorial, written
     * out of {@code integration_method.tutorial}, and the author's uploads.
     *
     * <p>A file that cannot be read is skipped rather than failing the rest, but the result says so:
     * a caller replacing what the work package holds must not read "could not be read" as "the author
     * removed it" and delete the copy the reviewer has.
     */
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
     * Brings a work package's files back in line with the revision after an edit, and says what that
     * took.
     *
     * <p>Needed because the description only points at the tutorial rather than reproducing it: an
     * author who rewrites the tutorial changes nothing the description shows, so without this the
     * reviewer opens the Files tab and reads the text that was submitted first. The same goes for a
     * sample added or withdrawn since.
     *
     * <p>Only the catalog's own uploads are touched, and only where they differ: a file whose content
     * still matches what the portal stores is left alone, so an edit that changed neither the tutorial
     * nor the samples leaves the Files tab untouched instead of churning it. Anything a reviewer
     * attached themselves belongs to the conversation, not to the submission, and is never removed.
     *
     * @return what changed, in reader's terms, for the comment that explains the edit; empty when
     * nothing changed or when the portal could not be asked, which is not worth failing the edit over
     */
    private List<String> refreshAttachments(int workPackageId, IntegrationMethod method) {
        SubmittedFiles submitted = collectFiles(method, workPackageId);

        Map<String, List<OpenProjectClient.Attachment>> ours;
        try {
            OptionalInt self = openProjectClient.findSelfId();
            if (self.isEmpty()) {
                // Without knowing which uploads are the catalog's own, every deletion is a guess at
                // somebody else's file and every upload is a duplicate. Better to leave it as it is.
                log.warn("Support portal did not say who the catalog signs in as, so the files of work"
                        + " package {} were left as they are", workPackageId);
                return List.of();
            }
            String selfHref = "/" + self.getAsInt();
            ours = openProjectClient.listAttachments(workPackageId).stream()
                    .filter(attachment -> attachment.authorHref().endsWith(selfHref))
                    .collect(Collectors.groupingBy(OpenProjectClient.Attachment::fileName,
                            LinkedHashMap::new, Collectors.toList()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while reading the files of support work package {}", workPackageId);
            return List.of();
        } catch (Exception e) {
            log.warn("Could not read the files of support work package {}, so they were left as they are: {}",
                    workPackageId, e.getMessage());
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
                // Already there, unchanged. Anything else of that name is a duplicate upload, and
                // dropping it is housekeeping rather than something the edit did.
                deleteOthers(workPackageId, present, same);
                continue;
            }
            // Uploaded before the old copy is dropped, so a refused upload leaves the reviewer with
            // the file as it was rather than with no file at all.
            OperationResult attached = attach(workPackageId, file);
            if (!attached.isCompleted()) {
                if (attached.isObsolete()) {
                    refused.add(file.fileName());
                }
                continue;
            }
            boolean replaced = deleteOthers(workPackageId, present, null);
            changes.add("`" + file.fileName() + "` " + (replaced ? "replaced" : "added"));
        }

        commentOnRefusedFiles(workPackageId, method, refused);

        if (!submitted.complete()) {
            // Something could not be read, so an attachment with no file behind it may still have one.
            log.warn("Not everything {}/{} carries could be read, so nothing was removed from work package {}",
                    method.getId(), method.getRevision(), workPackageId);
            return changes;
        }
        for (List<OpenProjectClient.Attachment> withdrawn : ours.values()) {
            for (OpenProjectClient.Attachment attachment : withdrawn) {
                if (delete(workPackageId, attachment)) {
                    changes.add("`" + attachment.fileName() + "` removed");
                }
            }
        }
        return changes;
    }

    /**
     * Removes every attachment of one name except the one being kept, which is either the copy that
     * already matches the submission or, after a fresh upload, nothing.
     *
     * @return whether anything was removed, i.e. whether the file was replaced rather than added
     */
    private boolean deleteOthers(int workPackageId, List<OpenProjectClient.Attachment> present,
                                 OpenProjectClient.Attachment keep) {
        if (present == null) {
            return false;
        }
        boolean removed = false;
        for (OpenProjectClient.Attachment attachment : present) {
            if (attachment != keep) {
                removed |= delete(workPackageId, attachment);
            }
        }
        return removed;
    }

    /** Removes one of the catalog's own attachments, reporting whether it is actually gone. */
    private boolean delete(int workPackageId, OpenProjectClient.Attachment attachment) {
        try {
            openProjectClient.deleteAttachment(attachment.id());
            log.info("Removed {} from support work package {}", attachment.fileName(), workPackageId);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while removing {} from support work package {}",
                    attachment.fileName(), workPackageId);
            return false;
        } catch (Exception e) {
            log.warn("Could not remove {} from support work package {}: {}",
                    attachment.fileName(), workPackageId, e.getMessage());
            return false;
        }
    }

    /** The content's md5, which is the digest the portal reports for what it stores. */
    private static String md5(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content));
        } catch (NoSuchAlgorithmException e) {
            // Every JVM has md5; without it a file simply never matches and is uploaded again.
            log.warn("No md5 available, so attached files cannot be compared: {}", e.getMessage());
            return null;
        }
    }

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
     * Attaches one of the author's uploaded files to the work package of the revision it belongs to.
     *
     * <p>The publish form uploads these in separate requests after the create call, so the work package
     * already exists and its description was written while the folder was still empty. Attaching on
     * arrival is what puts them in front of the reviewer; the portal notes each one in the work package's
     * activity by itself, so nothing is commented here.
     *
     * <p>A revision with no work package is given up on rather than retried, for the same reason as
     * {@link #appendConnector}: opening a work package attaches everything the revision carries at
     * that moment, so a file uploaded while the portal was down is attached by the operation that
     * finally opens it, not by this one.
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
            log.debug("Revision {}/{} has no work package to attach {} to",
                    event.methodId(), event.revision(), event.fileName());
            return OperationResult.obsolete("Revision " + event.methodId() + "/" + event.revision()
                    + " has no work package; the file is attached by the operation that opens it.");
        }
        return attachStoredFile(method.getSupportTicketId(), method, event.fileName());
    }

    /**
     * Reads one stored file and attaches it, letting the filesystem name its type.
     *
     * <p>A file that is gone from the catalog's storage is given up on: it was withdrawn, or the
     * revision it belonged to was, and no attempt will find it again. So is one the portal refuses
     * for its size - offering it again would fail the same way every time - and the reviewer is told
     * on the work package where to get it instead.
     */
    private OperationResult attachStoredFile(int workPackageId, IntegrationMethod method, String fileName) {
        Optional<SubmittedFile> file = readStoredFile(method, fileName, workPackageId);
        if (file.isEmpty()) {
            return OperationResult.obsolete("File " + fileName + " of " + method.getId() + "/"
                    + method.getRevision() + " could not be read from the catalog's storage.");
        }
        OperationResult attached = attach(workPackageId, file.get());
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
            // Windows often cannot name a type from the extension alone; the portal accepts the generic
            // one and still offers the file for download under its own name.
            return Optional.of(new SubmittedFile(fileName, content,
                    probed != null ? probed : "application/octet-stream"));
        } catch (Exception e) {
            log.warn("Could not read file {} of {}/{} to attach it to work package {}: {}",
                    fileName, method.getId(), method.getRevision(), workPackageId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * One attachment, best effort - a submission is not worth losing over a file the portal refused.
     *
     * <p>The two ways it can fail are worth keeping apart. A portal that is unreachable, or that
     * failed for a reason of its own, may take the file on the next attempt, so that is
     * {@link OperationResult#retry}. A file over the portal's size limit will be refused every time
     * it is offered, so that is {@link OperationResult#obsolete}: there is nothing to come back for,
     * and the reviewer is told where the file really is instead - see
     * {@link #commentOnRefusedFiles}.
     *
     * @return whether the file is now on the work package, and if not, whether offering it again
     * could change that - which a caller about to drop the copy it replaces, about to announce it,
     * or about to record the failure on a pending row has to know
     */
    private OperationResult attach(int workPackageId, SubmittedFile file) {
        try {
            openProjectClient.addAttachment(workPackageId, file.fileName(), file.content(), file.contentType());
            log.info("Attached {} ({} bytes) to support work package {}",
                    file.fileName(), file.content().length, workPackageId);
            return OperationResult.completed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while attaching {} to support work package {}", file.fileName(), workPackageId, e);
            return OperationResult.retry("Interrupted while attaching " + file.fileName()
                    + " to work package #" + workPackageId + ".");
        } catch (OpenProjectClient.AttachmentTooLargeException e) {
            log.warn("Support work package {} would not take {} ({} bytes): it is over the portal's"
                            + " attachment size limit, so it stays in the catalog only",
                    workPackageId, file.fileName(), file.content().length);
            return OperationResult.obsolete("Work package #" + workPackageId + " would not take "
                    + file.fileName() + " (" + file.content().length
                    + " bytes): it is over the support portal's attachment size limit.");
        } catch (Exception e) {
            log.error("Failed to attach {} to support work package {}: {}",
                    file.fileName(), workPackageId, e.getMessage());
            return OperationResult.retry("Could not attach " + file.fileName() + " to work package #"
                    + workPackageId + ": " + reason(e));
        }
    }

    /**
     * Tells the reviewer, on the work package itself, about files the portal would not take and
     * where to get them instead.
     *
     * <p>Without this the Files tab is simply short: the description names a sample the reviewer
     * cannot find, and nothing says whether it was never submitted or merely would not fit. The
     * files are in the catalog either way, so the comment says so and links to the revision's page.
     *
     * <p>One comment for however many files a run refused, rather than one each, so a submission
     * carrying several oversized samples does not bury the review conversation.
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

    /**
     * Subscribes everyone the submission concerns to its freshly opened work package, so each side
     * hears about it from the portal instead of watching the catalog for it:
     *
     * <ul>
     *   <li>the reviewers named in {@code openproject.watchers}, by portal login;</li>
     *   <li>the author, and the maintainer when that is a person, by the address in
     *       {@code catalog_users.email}. A maintainer that is an organization adds nobody of its
     *       own - its author speaks for it, and only people have an address at all, see
     *       {@link CatalogContactResolver}.</li>
     * </ul>
     *
     * <p>Best effort throughout, one address at a time: somebody the portal does not know, or who
     * cannot see the work package, is logged and skipped. The work package is already open and its
     * id already on the revision by the time this runs, and a missing watcher is not worth losing
     * that over - the review proceeds without any watcher at all, and the body names everyone
     * regardless.
     */
    private void addWatchers(int workPackageId, IntegrationMethod method) {
        // Portal user ids rather than names: one person can be reached by more than one route - a
        // configured reviewer login, and the same person as the author or as the maintainer - and
        // there is no reason to send the same request twice.
        Set<Integer> watching = new LinkedHashSet<>();

        for (String login : properties.watchers()) {
            OptionalInt userId = resolve(login, () -> openProjectClient.findUserIdByLogin(login), workPackageId);
            watch(workPackageId, login, userId, watching);
        }

        for (String name : distinct(method.getAuthor(), method.getMaintainer())) {
            String email = contactResolver.emailOf(name).orElse(null);
            if (email == null) {
                // An organization, or somebody the catalog holds no address for. The author covers
                // the first case, and the body names them all either way.
                log.debug("No contact address for '{}', not watching work package {}", name, workPackageId);
                continue;
            }
            watch(workPackageId, email, findByEmail(email, workPackageId), watching);
        }
    }

    /**
     * Attaches one resolved user, unless there is nobody to attach or they are attached already.
     * {@code watching} carries the ids attached so far and is added to.
     */
    private void watch(int workPackageId, String who, OptionalInt userId, Set<Integer> watching) {
        if (userId.isEmpty() || !watching.add(userId.getAsInt())) {
            return;
        }
        try {
            openProjectClient.addWatcher(workPackageId, userId.getAsInt());
            log.debug("Added '{}' as a watcher of support work package {}", who, workPackageId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while adding watchers to support work package {}", workPackageId);
        } catch (Exception e) {
            log.warn("Could not add '{}' as a watcher of support work package {}: {}",
                    who, workPackageId, e.getMessage());
        }
    }

    private OptionalInt findByEmail(String email, int workPackageId) {
        return resolve(email, () -> openProjectClient.findUserIdByEmail(email), workPackageId);
    }

    /**
     * Runs one portal lookup, reporting a miss and a failure the same way to the caller - neither
     * yields somebody to attach - while keeping them apart in the log: an unknown person is normal,
     * a failed query is not.
     */
    private OptionalInt resolve(String who, PortalLookup lookup, int workPackageId) {
        try {
            OptionalInt userId = lookup.get();
            if (userId.isEmpty()) {
                log.info("Support portal has no user for '{}', not watching work package {}",
                        who, workPackageId);
            }
            return userId;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while looking '{}' up in the support portal", who);
            return OptionalInt.empty();
        } catch (Exception e) {
            log.warn("Could not look '{}' up in the support portal: {}", who, e.getMessage());
            return OptionalInt.empty();
        }
    }

    /** One lookup on {@link OpenProjectClient}, so the error handling around it is written once. */
    @FunctionalInterface
    private interface PortalLookup {
        OptionalInt get() throws IOException, InterruptedException;
    }

    /**
     * The given names without blanks and without repetition, because an author who maintains their
     * own submission is named twice and would otherwise be resolved twice.
     */
    private static List<String> distinct(String... names) {
        return Stream.of(names)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
    }

    /**
     * The work package behind a revision's review and whether it says the reviewer may approve.
     * <p>
     * Restricted to the people the review concerns - the reviewer and the submitting side - via
     * the same ownership check that guards editing. The ticket carries the review conversation,
     * which is not part of the catalog's public face.
     * <p>
     * Beyond that it never throws: a portal that cannot be reached is reported through
     * {@link SupportTicketDto#error()} so the dialog can say so rather than fail.
     */
    // Deliberately not @Transactional: the portal call below can take seconds, and there is no
    // reason to hold a database connection open across it. The one read stands on its own.
    public SupportTicketDto describe(UUID methodId, String revision, String username) {
        IntegrationMethod method = integrationMethodRepository
                .findById(new IntegrationMethodId(methodId, revision))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Integration method not found: " + methodId + "/" + revision));

        // canEdit already lets a superuser through, so the reviewer needs no separate case.
        if (!authService.canEdit(username, method.getAuthor(), method.getMaintainer())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Not allowed to see the support ticket of " + methodId + "/" + revision);
        }

        if (!properties.enabled()) {
            return new SupportTicketDto(false, null, null, null, true, null);
        }

        Integer ticketId = method.getSupportTicketId();
        if (ticketId == null) {
            // Submitted before the portal integration existed, or the portal was unreachable then.
            // There is no conversation to wait for, so approval is not held up.
            return new SupportTicketDto(true, null, null, null, true, null);
        }

        String url = properties.workPackageUrl(ticketId);
        try {
            Optional<String> status = openProjectClient.readStatus(ticketId);
            if (status.isEmpty()) {
                return new SupportTicketDto(true, ticketId, url, null, false,
                        "Work package #" + ticketId + " no longer exists in the support portal.");
            }
            boolean ready = properties.isApprovalStatus(status.get());
            return new SupportTicketDto(true, ticketId, url, status.get(), ready, null);
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
