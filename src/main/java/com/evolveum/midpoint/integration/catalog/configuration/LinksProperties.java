/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * External documents the frontend links to. Kept in configuration so a moved page is a config
 * change, not a code change.
 */
@ConfigurationProperties(prefix = "catalog.links")
public record LinksProperties(
        String termsOfUse,
        String acceptableUsePolicy,
        String copyrightGuidelines,
        String githubTokenGuide
) {
}
