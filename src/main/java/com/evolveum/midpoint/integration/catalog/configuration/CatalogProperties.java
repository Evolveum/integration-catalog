/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

/**
 * How the catalog refers to itself when writing links other systems will follow.
 *
 * <p>Needed because the application cannot work its own public address out: behind a reverse proxy
 * the request says whatever the proxy chose to forward, and the one place a link is written - the
 * support work package opened after a submission commits - runs with no HTTP request in scope at
 * all.
 *
 * @param publicUrl base URL the catalog is reached at from a browser, e.g.
 *                  {@code https://catalog.evolveum.com}. Empty leaves the links out rather than
 *                  writing a broken one, so a deployment that has not set it loses nothing else.
 */
@ConfigurationProperties(prefix = "catalog")
public record CatalogProperties(String publicUrl) {

    /** Whether a public URL is configured, i.e. whether links can be written at all. */
    public boolean hasPublicUrl() {
        return publicUrl != null && !publicUrl.isBlank();
    }

    /**
     * Browser URL of an integration method revision's detail page, matching the frontend route
     * {@code applications/:appId/integration-method/:versionId/:revision/details}.
     *
     * @return the URL, or null when no public URL is configured or the revision cannot be addressed
     */
    public String integrationMethodUrl(UUID applicationId, UUID methodId, String revision) {
        if (!hasPublicUrl() || applicationId == null || methodId == null || revision == null) {
            return null;
        }
        return stripTrailingSlash(publicUrl) + "/applications/" + applicationId
                + "/integration-method/" + methodId + "/" + revision + "/details";
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
