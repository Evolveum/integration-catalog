/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection to the Gravitee instance that mints API keys, so pointing the catalog at another one
 * is a change of properties only.
 */
@ConfigurationProperties(prefix = "gravitee")
public record GraviteeProperties(
        String managementUrl,
        String organizationId,
        String environmentId,
        String apiId,
        String planId,
        String token
) {

    /** Defaults, so a deployment names only the URL, the API, the plan and the token. */
    public GraviteeProperties {
        organizationId = blankToDefault(organizationId, "DEFAULT");
        environmentId = blankToDefault(environmentId, "DEFAULT");
    }

    /** Whether keys can be issued at all; the rest of the catalog works either way. */
    public boolean enabled() {
        return notBlank(managementUrl) && notBlank(apiId) && notBlank(planId) && notBlank(token);
    }

    /** Applications live on v1, subscriptions on v2. */
    public String managementV1Base() {
        return stripTrailingSlash(managementUrl)
                + "/management/organizations/" + organizationId + "/environments/" + environmentId;
    }

    public String managementV2Base() {
        return stripTrailingSlash(managementUrl)
                + "/management/v2/organizations/" + organizationId + "/environments/" + environmentId;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToDefault(String value, String fallback) {
        return notBlank(value) ? value : fallback;
    }

    private static String stripTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
