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
        String apiKey,
        String project,
        Integer typeId,
        List<String> watchers,
        boolean trustAllCertificates,
        List<CustomField> customField,
        Boolean enabled
) {

    public record CustomField(
            int id,
            int valueId
    ) {}

    /**
     * Defaults, so a deployment configuring only URL, token and project still works. The numeric ids
     * follow their constant when unset, so both always answer with the id to send.
     */
    public OpenProjectProperties {
        // An empty "openproject.watchers=" binds to a list holding one blank string, not to no list.
        watchers = watchers == null ? List.of() : watchers.stream()
                .filter(watcher -> watcher != null && !watcher.isBlank())
                .map(String::trim)
                .toList();
        enabled = enabled == null || enabled;
    }

    /** Whether the portal is configured at all; when false the catalog behaves as it did before. */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled) && url != null && !url.isBlank();
    }

    /** Browser URL of a work package, for the reviewer's and the author's links. */
    public String workPackageUrl(int workPackageId) {
        return stripTrailingSlash(url) + "/work_packages/" + workPackageId;
    }

    /** Base of the REST API, without a trailing slash. */
    public String apiBase() {
        return stripTrailingSlash(url) + "/api/v3";
    }

    public String basicUrl() {
        return stripTrailingSlash(url);
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
