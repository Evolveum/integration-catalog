/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.util.UUID;

/**
 * DTO for listing integration methods in the upload form.
 * Contains data from: integration_method, connector, connector_bundle, connector_bundle_version.
 */
public record ImplementationListItemDto(
        UUID id,
        String name,
        String description,
        String publishedDate,
        String version,                   // connector_bundle_version.bundle_version
        String displayName,
        String maintainer,                // connector_bundle.maintainer
        String licenseType,               // connector_bundle.license
        String implementationDescription,
        String browseLink,                // connector_bundle_version.browse_link
        String ticketingLink,             // connector_bundle.ticketing_link
        String buildFramework,            // connector_bundle_version.build_framework
        String gitCloneUrl,               // connector_bundle_version.git_clone_ULR
        String pathToProjectDirectory,    // connector_bundle_version.path_to_project
        String className                  // connector.fully_qualified_class_name
) {}
