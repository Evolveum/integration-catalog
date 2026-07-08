/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.util.List;
import java.util.UUID;

/**
 * DTO for the active connectors listing endpoint.
 * Contains a subset of ApplicationCardDto fields, excluding logo metadata,
 * lifecycle state (always ACTIVE), vote count, and request ID.
 */
public record ActiveConnectorDto(
        UUID id,                             // application.id
        String displayName,                  // application.display_name
        String description,                  // application.description
        List<CountryOfOriginDto> origins,    // country_of_origin via application_origin
        List<ApplicationTagDto> categories,  // application_tag (CATEGORY) via application_application_tag
        List<ApplicationTagDto> tags,        // application_tag via application_application_tag
        List<String> capabilities,           // capability.name via integration_method_capability
        List<String> frameworks,             // connector_bundle.framework
        List<String> midpointVersions        // midpoint_version.version
) {}
