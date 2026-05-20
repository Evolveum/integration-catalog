/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tutorial.storage")
public record TutorialStorageProperties(
        String basePath,
        long maxSizeBytes
) {
    public static final long DEFAULT_MAX_SIZE_BYTES = 20 * 1024 * 1024; // 20 MB

    public TutorialStorageProperties {
        if (basePath == null || basePath.isBlank()) {
            basePath = "/data/tutorials";
        }
        if (maxSizeBytes <= 0) {
            maxSizeBytes = DEFAULT_MAX_SIZE_BYTES;
        }
    }
}
