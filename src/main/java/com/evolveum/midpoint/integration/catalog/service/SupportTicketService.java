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
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads back the work package a submitted revision is paired with, where the author and the reviewer
 * discuss the submission. Writing to the portal belongs to the handlers named after each operation
 * ({@link OpenWorkPackageHandler} and its siblings); this is the read the catalog's own screens make.
 * The pairing lives on the revision row, so a fork gets its own while an in-place edit keeps one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupportTicketService {

    private final IntegrationMethodRepository integrationMethodRepository;
    private final OpenProjectClient openProjectClient;
    private final OpenProjectProperties properties;
    private final AuthService authService;

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
