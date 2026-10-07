/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import com.evolveum.midpoint.integration.catalog.object.ApplicationVersion;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ApplicationVersionDto(
        Integer id,             // application_version.id, null for a version being added
        @NotBlank(message = "Application version cannot be empty.")
        @Size(max = ApplicationVersion.VERSION_MAX,
                message = "Application version can be at most " + ApplicationVersion.VERSION_MAX + " characters.")
        String version          // application_version.version
) {
    public static ApplicationVersionDto of(ApplicationVersion v) {
        return new ApplicationVersionDto(v.getId(), v.getVersion());
    }
}
