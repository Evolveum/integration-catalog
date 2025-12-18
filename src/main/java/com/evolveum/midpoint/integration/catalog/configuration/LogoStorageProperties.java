/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for logo file storage.
 */
@ConfigurationProperties(prefix = "logo.storage")
public record LogoStorageProperties(
        String basePath,
        long maxSizeBytes
) {
    /**
     * Default max size: 5MB
     */
    public static final long DEFAULT_MAX_SIZE_BYTES = 5 * 1024 * 1024;

    public LogoStorageProperties {
        if (basePath == null || basePath.isBlank()) {
            basePath = "/data/logos";
        }
        if (maxSizeBytes <= 0) {
            maxSizeBytes = DEFAULT_MAX_SIZE_BYTES;
        }
    }
}
