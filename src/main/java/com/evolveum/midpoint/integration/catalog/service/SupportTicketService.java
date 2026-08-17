/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.configuration.OpenProjectProperties;
import com.evolveum.midpoint.integration.catalog.dto.SupportTicketDto;
import com.evolveum.midpoint.integration.catalog.integration.OpenProjectClient;
import com.evolveum.midpoint.integration.catalog.object.Application;
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
import java.util.ArrayList;
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

    private final IntegrationMethodRepository integrationMethodRepository;
    private final OpenProjectClient openProjectClient;
    private final OpenProjectProperties properties;
    private final AuthService authService;
    private final SupportTicketDescriptionBuilder descriptionBuilder;
    private final CatalogContactResolver contactResolver;

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
            return;
        }

        try {
            int workPackageId = openProjectClient.createWorkPackage(
                    subjectFor(method), descriptionBuilder.build(method));
            method.setSupportTicketId(workPackageId);
            log.info("Opened support work package {} for integration method {}/{}",
                    workPackageId, event.methodId(), event.revision());
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
     * Subscribes everyone the submission concerns to its freshly opened work package, so each side
     * hears about it from the portal instead of watching the catalog for it:
     *
     * <ul>
     *   <li>the reviewers named in {@code openproject.watchers}, by portal login;</li>
     *   <li>the author, and the maintainer when that is a person, by the address the catalog holds
     *       for them. A maintainer that is an organization adds nobody: its author already speaks
     *       for it. Only if neither could be attached does the organization's shared mailbox get
     *       tried - see {@link CatalogContactResolver#notificationTargets(String)}.</li>
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

        // The submitting side, and the shared mailboxes standing behind it. The fallbacks are held
        // until the end rather than tried per party, because they answer one question about the
        // whole side - did anybody at all get attached - and an organization's mailbox is not
        // wanted while its author is already watching.
        List<String> fallbacks = new ArrayList<>();
        int attached = 0;
        for (String name : distinct(method.getAuthor(), method.getMaintainer())) {
            CatalogContactResolver.NotificationTargets targets = contactResolver.notificationTargets(name);
            if (targets.isEmpty()) {
                log.debug("No contact address for '{}', not watching work package {}", name, workPackageId);
                continue;
            }
            for (String email : targets.addresses()) {
                if (watch(workPackageId, email, findByEmail(email, workPackageId), watching)) {
                    attached++;
                }
            }
            if (targets.fallback() != null) {
                fallbacks.add(targets.fallback());
            }
        }
        if (attached == 0) {
            for (String fallback : fallbacks) {
                watch(workPackageId, fallback, findByEmail(fallback, workPackageId), watching);
            }
        }
    }

    /**
     * Attaches one resolved user, if there is one and it is not attached already.
     *
     * @return whether the user is now watching, which decides whether an organization still needs
     * its fallback mailbox tried
     */
    private boolean watch(int workPackageId, String who, OptionalInt userId, Set<Integer> watching) {
        if (userId.isEmpty()) {
            return false;
        }
        if (!watching.add(userId.getAsInt())) {
            return true;
        }
        try {
            openProjectClient.addWatcher(workPackageId, userId.getAsInt());
            log.debug("Added '{}' as a watcher of support work package {}", who, workPackageId);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while adding watchers to support work package {}", workPackageId);
            return false;
        } catch (Exception e) {
            log.warn("Could not add '{}' as a watcher of support work package {}: {}",
                    who, workPackageId, e.getMessage());
            return false;
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

    private String subjectFor(IntegrationMethod method) {
        Application application = method.getApplication();
        String applicationName = application != null ? application.getDisplayName() : null;
        String methodName = method.getDisplayName() != null ? method.getDisplayName() : "integration method";
        return "Review: " + (applicationName != null ? applicationName + " - " : "")
                + methodName + " " + method.getRevision();
    }

}
