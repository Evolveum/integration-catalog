/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.util.List;
import java.util.UUID;

public record UploadIntegrationMethodDto(
        UUID id,                    // integration_method.id (null for new)
        String displayName,         // integration_method.display_name
        String revision,            // integration_method.revision
        String description,         // integration_method.description
        String tutorial,            // integration_method.tutorial
        List<Integer> typeIds,      // integration_method_type.id
        Integer midpointMinVersion, // midpoint_version.id (FK for min version)
        Integer midpointMaxVersion  // midpoint_version.id (FK for max version)
) {
}
