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
     * Opens a work package for a submitted revision, or rewrites the one it already has. Runs after
     * the submitting transaction commits, in its own transaction, so an unreachable portal costs the
     * author nothing but time: the operation stays pending and the scheduled retry opens the work
     * package once the portal is back. Until then {@link #describe} reports no ticket, so the
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
     * Rewrites the work package of a revision edited while under review, so the reviewer reads the
     * submission as it stands. A portal that cannot be reached leaves the operation pending for the
     * scheduled retry.
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
                    tutorialChange(previous, method));
        } catch (Exception e) {
            log.warn("Could not work out what {}/{} changes about {}: {}",
                    event.methodId(), event.revision(), event.previousRevision(), e.getMessage());
            return;
        }
        comment(workPackageId, delta);
    }

    /**
     * Whether the tutorial differs between two revisions, worded as {@link #refreshAttachments} does.
     * Needed separately because the description only points at the tutorial, never reproduces it.
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
     * Puts the tutorial ({@value #TUTORIAL_ATTACHMENT}) and every sample uploaded so far on the work
     * package's Files tab - attachments, because a tutorial is an unbounded {@code text} column and a
     * sample is not text at all. On a first submission the samples arrive later, via
     * {@link #attachTutorialFile}.
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
     * The tutorial and the author's uploads, read and ready to attach. An unreadable file is skipped,
     * and the result says so, so a caller does not mistake it for one the author removed.
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
                deleteOthers(workPackageId, present, same);
                continue;
            }

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

    /**
     * Subscribes everyone the submission concerns to its work package: the reviewers in
     * {@code openproject.watchers} by login, and the submitting side by {@code catalog_users.email}.
     * Best effort - anyone the portal does not know is logged and skipped.
     */
    private void addWatchers(int workPackageId, IntegrationMethod method) {
        Set<Integer> watching = new LinkedHashSet<>();

        for (String login : properties.watchers()) {
            OptionalInt userId = resolve(login, () -> openProjectClient.findUserIdByLogin(login), workPackageId);
            watch(workPackageId, login, userId, watching);
        }

        for (String name : distinct(method.getAuthor(), method.getMaintainer())) {
            String email = contactResolver.emailOf(name).orElse(null);
            if (email == null) {
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
     * The work package behind a revision's review and whether it lets the reviewer approve. Restricted
     * to the reviewer and the submitting side by the same ownership check that guards editing. Never
     * throws for a portal problem - that is reported through {@link SupportTicketDto#error()}.
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
