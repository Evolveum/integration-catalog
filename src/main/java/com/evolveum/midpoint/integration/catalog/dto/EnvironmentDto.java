/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

/**
 * Deployment environment info served to the frontend. {@code environment} is the
 * value of the {@code catalog.environment} property ({@code inStaging} or
 * {@code production}); the frontend shows the staging banner only for {@code inStaging}.
 */
public record EnvironmentDto(
        String environment
) {
}
