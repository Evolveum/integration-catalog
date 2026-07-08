/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * DTO for request form submissions that create both an Application and a Request.
 * Created by TomasS.
 */
public record RequestFormDto(
        @NotBlank String integrationApplicationName, // → application.display_name
        String deploymentType,                        // → application_tag (DEPLOYMENT type)
        @NotBlank String description,                 // → application.description
        String systemVersion,                         // request context field
        String contactEmail,                          // request context field
        Boolean openToCollaborate,                    // request context field
        String requester,                             // → request.requester / catalog_users.username
        List<ObjectClassCapabilityEntry> capabilities // → object_class_capabilities
) {
    public record ObjectClassCapabilityEntry(
            String objectName,        // object_class_capabilities.object_name
            List<String> capabilities // capability.name items
    ) {}
}
