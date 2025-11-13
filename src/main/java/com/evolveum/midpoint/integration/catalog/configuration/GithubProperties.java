/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Created by Dominik.
 */
@ConfigurationProperties(prefix = "github")
public record GithubProperties (
        String apiToken,
        String groupPath,
        String templatePath
) {
}
