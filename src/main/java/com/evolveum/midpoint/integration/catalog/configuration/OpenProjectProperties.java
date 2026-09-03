/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Connection to the support portal holding the review conversation for a submitted integration
 * method. Configured entirely here, so another portal or target project is a change of properties
 * only.
 */
@ConfigurationProperties(prefix = "openproject")
public record OpenProjectProperties(
        String url,
        String username,
        String password,
        String project,
        OpenProjectType type,
        Integer typeId,
        OpenProjectStatus initialStatus,
        Integer initialStatusId,
        List<String> approvalStatuses,
        List<String> watchers,
        boolean trustAllCertificates
) {

    /**
     * Defaults, so a deployment configuring only URL, token and project still works. The numeric ids
     * follow their constant when unset, so both always answer with the id to send.
     */
    public OpenProjectProperties {
        username = (username == null || username.isBlank()) ? "apikey" : username;
        type = type != null ? type : OpenProjectType.TASK;
        initialStatus = initialStatus != null ? initialStatus : OpenProjectStatus.NEW;
        typeId = typeId != null ? typeId : type.id();
        initialStatusId = initialStatusId != null ? initialStatusId : initialStatus.id();
        approvalStatuses = (approvalStatuses == null || approvalStatuses.isEmpty())
                ? List.of(OpenProjectStatus.TESTED.title(), OpenProjectStatus.CLOSED.title())
                : List.copyOf(approvalStatuses);
        // An empty "openproject.watchers=" binds to a list holding one blank string, not to no list.
        watchers = watchers == null ? List.of() : watchers.stream()
                .filter(login -> login != null && !login.isBlank())
                .map(String::trim)
                .toList();
    }

    /**
     * Whether a work package reporting {@code status} lets the reviewer approve. Matched loosely, so
     * {@code Tested}, {@code tested} and {@code TESTED} are the same status.
     */
    public boolean isApprovalStatus(String status) {
        if (status == null) {
            return false;
        }
        String normalized = OpenProjectStatus.normalize(status);
        return !normalized.isEmpty() && approvalStatuses.stream()
                .anyMatch(candidate -> OpenProjectStatus.normalize(candidate).equals(normalized));
    }

    /** Whether the portal is configured at all; when false the catalog behaves as it did before. */
    public boolean enabled() {
        return url != null && !url.isBlank();
    }

    /** Browser URL of a work package, for the reviewer's and the author's links. */
    public String workPackageUrl(int workPackageId) {
        return stripTrailingSlash(url) + "/work_packages/" + workPackageId;
    }

    /** Base of the REST API, without a trailing slash. */
    public String apiBase() {
        return stripTrailingSlash(url) + "/api/v3";
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
