/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.configuration.OpenProjectProperties;
import com.evolveum.midpoint.integration.catalog.dto.SupportTicketDto;
import com.evolveum.midpoint.integration.catalog.integration.OpenProjectClient;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethod;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodId;
import com.evolveum.midpoint.integration.catalog.repository.IntegrationMethodRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
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

    private final IntegrationMethodRepository integrationMethodRepository;
    private final OpenProjectClient openProjectClient;
    private final OpenProjectProperties properties;
    private final AuthService authService;
    private final SupportTicketDescriptionBuilder descriptionBuilder;
    private final CatalogContactResolver contactResolver;
    private final TutorialStorageService tutorialStorageService;

    /**
     * Opens a work package for a freshly submitted revision, unless it already has one or the
     * portal is not configured.
     *
     * <p>Runs after the submitting transaction has committed, in a transaction of its own: the
     * submission is already durable by then, so a portal that is down, slow or misconfigured costs
     * the author nothing but a missing ticket. Reviewers of such a revision are not blocked either
     * — {@link #describe} reports no ticket, and the approval dialog then has nothing to wait for.
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onIntegrationMethodSubmitted(IntegrationMethodSubmittedEvent event) {
        if (!properties.enabled()) {
            return;
        }
        IntegrationMethod method = integrationMethodRepository
                .findById(new IntegrationMethodId(event.methodId(), event.revision()))
                .orElse(null);
        if (method == null) {
            // The revision was superseded between the commit and this listener (an in-place edit
            // deletes the row it replaces); whatever replaced it carries the ticket forward.
            return;
        }
        if (method.getSupportTicketId() != null) {
            // Not a new submission: an edit of one already under review, which carried its work package
            // across. The body has to follow the edit, or the reviewer reads what was first sent.
            rewriteWorkPackage(method.getSupportTicketId(), method, event);
            return;
        }

        try {
            int workPackageId = openProjectClient.createWorkPackage(
                    event.flow().taskName(method.getDisplayName()), descriptionBuilder.build(method));
            method.setSupportTicketId(workPackageId);
            log.info("Opened support work package {} for integration method {}/{}",
                    workPackageId, event.methodId(), event.revision());
            attachFiles(workPackageId, method);
            addWatchers(workPackageId, method);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while opening a support work package for integration method {}/{}",
                    event.methodId(), event.revision(), e);
        } catch (Exception e) {
            log.error("Failed to open a support work package for integration method {}/{}: {}",
                    event.methodId(), event.revision(), e.getMessage());
        }
    }

    /**
     * Rewrites the work package of a revision that was edited while it was under review, so what the
     * reviewer reads is the submission as it stands rather than as it was first sent.
     *
     * <p>Edits reach here because an in-place edit replaces the revision row and carries its work
     * package to the replacement, which then raises a submitted event like any other; the ticket id
     * on the row is what tells the two apart.
     *
     * <p>Best effort, like everything else here: the edit is already committed and a portal that
     * cannot be reached must not cost the author their change. The work package keeps the older body
     * in that case, which the next edit rewrites.
     */
    private void rewriteWorkPackage(int workPackageId, IntegrationMethod method,
                                    IntegrationMethodSubmittedEvent event) {
        try {
            String subject = SubmissionFlow.renamed(
                    openProjectClient.readSubject(workPackageId).orElse(null), method.getDisplayName());
            if (subject == null) {
                // Nothing to keep - the portal has no subject to build on, so name it by this flow.
                subject = event.flow().taskName(method.getDisplayName());
            }
            if (openProjectClient.updateWorkPackage(workPackageId, subject, descriptionBuilder.build(method))) {
                log.info("Updated support work package {} after an edit of integration method {}/{}",
                        workPackageId, event.methodId(), event.revision());
            } else {
                log.warn("Support work package {} of integration method {}/{} no longer exists, "
                        + "so the edit was not written to it", workPackageId, event.methodId(), event.revision());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while updating support work package {} of integration method {}/{}",
                    workPackageId, event.methodId(), event.revision(), e);
        } catch (Exception e) {
            log.error("Failed to update support work package {} of integration method {}/{}: {}",
                    workPackageId, event.methodId(), event.revision(), e.getMessage());
        }
    }

    /**
     * Appends a connector added to a revision that is already under review to that revision's existing
     * work package, so the reviewer sees the submission grow instead of finding a second work package
     * about the same review.
     *
     * <p>Same shape as {@link #onIntegrationMethodSubmitted}: after the commit, in its own transaction,
     * and a portal that cannot be reached costs nothing but the comment. A revision whose work package
     * was never opened - submitted before the portal integration existed, or while it was down - has
     * nothing to append to; the connector is in the catalog either way.
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onConnectorAddedToReview(ConnectorAddedToReviewEvent event) {
        if (!properties.enabled()) {
            return;
        }
        IntegrationMethod method = integrationMethodRepository
                .findById(new IntegrationMethodId(event.methodId(), event.revision()))
                .orElse(null);
        if (method == null || method.getSupportTicketId() == null) {
            return;
        }

        try {
            openProjectClient.addComment(method.getSupportTicketId(),
                    descriptionBuilder.buildConnectorAddendum(method, event.connectorId()));
            log.info("Appended connector {} to support work package {} of integration method {}/{}",
                    event.connectorId(), method.getSupportTicketId(), event.methodId(), event.revision());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while appending connector {} to support work package {}",
                    event.connectorId(), method.getSupportTicketId(), e);
        } catch (Exception e) {
            log.error("Failed to append connector {} to support work package {}: {}",
                    event.connectorId(), method.getSupportTicketId(), e.getMessage());
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
     * {@link #onTutorialFileAdded}. An edited or upgraded revision, on the other hand, already carries
     * its predecessor's files, and they are attached here.
     */
    private void attachFiles(int workPackageId, IntegrationMethod method) {
        String tutorial = method.getTutorial();
        if (tutorial != null && !tutorial.isBlank()) {
            attach(workPackageId, TUTORIAL_ATTACHMENT,
                    tutorial.getBytes(StandardCharsets.UTF_8), "text/markdown");
        }

        List<String> files;
        try {
            files = tutorialStorageService.listTutorialFiles(method.getId(), method.getRevision());
        } catch (Exception e) {
            log.warn("Could not list tutorial files of {}/{} to attach them to work package {}: {}",
                    method.getId(), method.getRevision(), workPackageId, e.getMessage());
            return;
        }
        for (String fileName : files) {
            attachStoredFile(workPackageId, method, fileName);
        }
    }

    /**
     * Attaches one of the author's uploaded files to the work package of the revision it belongs to.
     *
     * <p>The publish form uploads these in separate requests after the create call, so the work package
     * already exists and its description was written while the folder was still empty. Attaching on
     * arrival is what puts them in front of the reviewer; the portal notes each one in the work package's
     * activity by itself, so nothing is commented here.
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTutorialFileAdded(TutorialFileAddedEvent event) {
        if (!properties.enabled()) {
            return;
        }
        IntegrationMethod method = integrationMethodRepository
                .findById(new IntegrationMethodId(event.methodId(), event.revision()))
                .orElse(null);
        if (method == null || method.getSupportTicketId() == null) {
            // No work package to attach it to: the revision is not under review, or it was submitted
            // before the portal integration existed. The file is stored in the catalog either way.
            return;
        }
        attachStoredFile(method.getSupportTicketId(), method, event.fileName());
    }

    /** Reads one stored file and attaches it, letting the filesystem name its type. */
    private void attachStoredFile(int workPackageId, IntegrationMethod method, String fileName) {
        byte[] content;
        String contentType;
        try {
            Path file = tutorialStorageService.resolveTutorialFile(method.getId(), method.getRevision(), fileName);
            content = Files.readAllBytes(file);
            String probed = Files.probeContentType(file);
            // Windows often cannot name a type from the extension alone; the portal accepts the generic
            // one and still offers the file for download under its own name.
            contentType = probed != null ? probed : "application/octet-stream";
        } catch (Exception e) {
            log.warn("Could not read file {} of {}/{} to attach it to work package {}: {}",
                    fileName, method.getId(), method.getRevision(), workPackageId, e.getMessage());
            return;
        }
        attach(workPackageId, fileName, content, contentType);
    }

    /** One attachment, best effort - a submission is not worth losing over a file the portal refused. */
    private void attach(int workPackageId, String fileName, byte[] content, String contentType) {
        try {
            openProjectClient.addAttachment(workPackageId, fileName, content, contentType);
            log.info("Attached {} ({} bytes) to support work package {}", fileName, content.length, workPackageId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while attaching {} to support work package {}", fileName, workPackageId, e);
        } catch (Exception e) {
            log.error("Failed to attach {} to support work package {}: {}", fileName, workPackageId, e.getMessage());
        }
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
