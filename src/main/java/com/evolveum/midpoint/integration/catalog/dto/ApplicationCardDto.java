/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.util.List;
import java.util.UUID;

/**
 * DTO for application card display in list view
 */
public record ApplicationCardDto(
        UUID id,                             // application.id
        String displayName,                  // application.display_name
        String description,                  // application.description
        String logoPath,                     // application.logo_path
        String lifecycleState,               // application.lifecycle_state
        List<CountryOfOriginDto> origins,    // country_of_origin via application_origin
        List<ApplicationTagDto> categories,  // application_tag (CATEGORY) via application_application_tag
        List<ApplicationTagDto> tags,        // application_tag via application_application_tag
        List<String> capabilities,           // capability.name via integration_method_capability
        Long requestId,                      // request.id
        Long voteCount,                      // computed: count of vote rows
        List<String> frameworks,             // connector_bundle.framework
        List<String> midpointVersions,       // midpoint_version.version
        String currentMidpointVersion,       // midpoint_version.version (where is_current = true)
        List<String> integrationMethodTypes, // integration_method_type.display_name via integration_method
        List<String> maintainers             // Evolveum/Partner/Community, derived from catalog_users.role
) {}
