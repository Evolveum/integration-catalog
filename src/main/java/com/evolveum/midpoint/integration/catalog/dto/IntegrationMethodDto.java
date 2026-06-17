/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record IntegrationMethodDto(
        UUID id,                                                    // integration_method.id
        String description,                                         // integration_method.description
        List<String> implementationTags,                            // integration_method_type.name
        List<String> capabilities,                                  // capability.name via integration_method_capability
        List<ObjectClassCapabilityDto> objectClassCapabilities,     // object_class_capabilities
        String connectorVersion,                                    // connector_bundle_version.bundle_version
        String systemVersion,                                       // integration_method.system_version
        LocalDate releasedDate,                                     // connector_bundle_version.created_at
        String author,                                              // connector.author
        Integer organizationId,                                     // connector.organization_id
        String lifecycleState,                                      // integration_method.lifecycle_state
        String downloadLink,                                        // generated download URL
        String framework,                                           // connector_bundle.framework
        String errorMessage,                                        // integration_method.error_message
        Long downloadCount,                                         // computed: count of download rows
        Integer midpointMinVersionId,                               // integration_method.midpoint_min_version_id (FK → midpoint_version.id)
        Integer midpointMaxVersionId,                               // integration_method.midpoint_max_version_id (FK → midpoint_version.id)
        String connectorDisplayName,                                // connector.display_name
        List<String> integMethodTypes,                              // integration_method_type.name
        String revision,                                            // integration_method.revision
        String displayName,                                         // integration_method.display_name
        String tutorial,                                            // integration_method.tutorial
        String filePath                                             // integration_method.file_path
) {}