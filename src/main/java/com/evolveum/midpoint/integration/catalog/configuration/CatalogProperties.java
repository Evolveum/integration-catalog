/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

/**
 * How the catalog refers to itself in links other systems follow. Configured because the address
 * cannot be inferred: behind a reverse proxy the request reports whatever the proxy forwards, and
 * links are written with no request in scope at all.
 */
@ConfigurationProperties(prefix = "catalog")
public record CatalogProperties(String publicUrl) {

    /** Whether a public URL is configured, i.e. whether links can be written at all. */
    public boolean hasPublicUrl() {
        return publicUrl != null && !publicUrl.isBlank();
    }

    /**
     * Browser URL of an integration method revision's detail page.
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
