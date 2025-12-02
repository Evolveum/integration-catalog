/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * DTO for listing implementations in the upload form.
 * Contains data from multiple tables: implementation, connector_bundle, bundle_version, and implementation_version.
 */
public record ImplementationListItemDto(
        UUID id,                          // implementation.id
        String name,                      // implementation.display_name (for list display)
        String description,               // implementation_version.description (for list display)
        String publishedDate,             // implementation_version.publish_date formatted
        String version,                   // bundle_version.connector_version
        String displayName,               // implementation.display_name
        String maintainer,                // connector_bundle.maintainer
        String licenseType,               // connector_bundle.license
        String implementationDescription, // implementation_version.description
        String browseLink,                // bundle_version.browse_link
        String ticketingLink,             // connector_bundle.ticketing_system_link
        String buildFramework,            // bundle_version.build_framework
        String checkoutLink,              // bundle_version.checkout_link
        String pathToProjectDirectory,     // bundle_version.path_to_project
        String className                  // implmentation_version.class_name
) {}
