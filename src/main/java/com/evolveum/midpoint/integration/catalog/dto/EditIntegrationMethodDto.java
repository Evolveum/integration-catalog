/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.util.List;

public record EditIntegrationMethodDto(
        String displayName,                                          // integration_method.display_name
        String description,                                          // integration_method.description
        String tutorial,                                             // integration_method.tutorial
        List<IntegrationMethodCapabilityGroupDto> capabilities,      // integration_method_capability + items
        boolean removeFile,                                          // true → clear integration_method.file_path
        boolean minorBump,                                           // true → increment minor (x.Y.z → x.Y+1.1), false → patch (x.y.Z → x.y.Z+1)
        Integer midpointMinVersion,                                  // integration_method.midpoint_minversion (FK → midpoint_version.id)
        Integer midpointMaxVersion                                   // integration_method.midpoint_maxversion (FK → midpoint_version.id)
) {}
