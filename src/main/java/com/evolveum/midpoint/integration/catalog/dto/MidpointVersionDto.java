/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

public record MidpointVersionDto(
        Integer id,           // midpoint_version.id
        String version,       // midpoint_version.version
        String versionName,   // midpoint_version.version_name
        boolean isCurrent     // midpoint_version.is_current
) {}
