/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.configuration.OpenProjectProperties;
import com.evolveum.midpoint.integration.catalog.integration.OpenProjectClient;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethod;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Subscribes both sides of a review to its work package, so neither has to watch the catalog for a
 * submission. Anyone unknown, not permitted or unreachable is logged and skipped - the submission and
 * its work package stand either way. Logs name the role (author, maintainer, configured watcher),
 * never the login or the address, as logs are collected centrally.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupportTicketWatchers {

    private final OpenProjectClient openProjectClient;
    private final OpenProjectProperties properties;

    /** The configured reviewing side, plus the author and the maintainer of the revision. */
    public void addAll(int workPackageId, IntegrationMethod method) {
        Set<Integer> watching = new LinkedHashSet<>();

        List<String> configured = properties.watchers();
        for (int i = 0; i < configured.size(); i++) {
            String login = configured.get(i);
            String role = "configured watcher #" + (i + 1);
            OptionalInt userId = resolveUserIdOfOpUser(role, () -> openProjectClient.findUserIdByLogin(login), workPackageId);
            addWatcher(workPackageId, role, userId, watching);
        }

        String author = method.getAuthor().getUsername();
        for (String name : checkNullAndDistinctNames(author, method.getMaintainer().getUsername())) {
            String role = name.equals(author) ? "author" : "maintainer";
            OptionalInt userId = attempt(role, () -> openProjectClient.findUserIdByLogin(name));
            if (userId.isEmpty()) {
                // No account under that login: fall back to the address the row carries, which the
                // author has and nobody else does.
                String email = name.equals(author) ? method.getAuthor().getEmail() : null;
                if (email == null) {
                    log.debug("No portal login and no contact address for the {}, "
                            + "not watching work package {}", role, workPackageId);
                    continue;
                }
                role = role + " (by email)";
                userId = resolveUserIdOfOpUser(role, () -> openProjectClient.findUserIdByEmail(email), workPackageId);
            }
            addWatcher(workPackageId, role, userId, watching);
        }
    }

    private void addWatcher(int workPackageId, String role, OptionalInt userId, Set<Integer> watching) {
        if (userId.isEmpty() || !watching.add(userId.getAsInt())) {
            return;
        }
        try {
            openProjectClient.addWatcher(workPackageId, userId.getAsInt());
            log.debug("Added the {} as a watcher of support work package {}", role, workPackageId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while adding watchers to support work package {}", workPackageId, e);
        } catch (Exception e) {
            log.error("Could not add the {} as a watcher of support work package {}",
                    role, workPackageId, e);
        }
    }

    private OptionalInt resolveUserIdOfOpUser(String role, PortalLookup lookup, int workPackageId) {
        OptionalInt userId = attempt(role, lookup);
        if (userId.isEmpty()) {
            log.info("Support portal has no user for the {}, not watching work package {}",
                    role, workPackageId);
        }
        return userId;
    }

    /**
     * One portal lookup without reporting a miss, for a caller that has another way left to try -
     * see {@link #addAll}, where a login that is not a portal account is an ordinary step on the
     * way to the address rather than something to warn about. A failed query is still logged.
     */
    private OptionalInt attempt(String role, PortalLookup lookup) {
        try {
            return lookup.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while looking the {} up in the support portal", role, e);
            return OptionalInt.empty();
        } catch (Exception e) {
            log.error("Could not look the {} up in the support portal", role, e);
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
}
