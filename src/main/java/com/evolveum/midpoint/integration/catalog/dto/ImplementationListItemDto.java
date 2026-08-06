/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.util.List;
import java.util.UUID;

/**
 * DTO for listing integration methods in the upload form.
 * Contains data from: integration_method, connector, connector_bundle, connector_bundle_version.
 */
public record ImplementationListItemDto(
        UUID id,                           // integration_method.id
        Integer connectorId,               // connector.id (identifies the linked connector for editing)
        String name,                       // integration_method.display_name
        String description,                // integration_method.description
        String publishedDate,              // connector_bundle_version.released_date (null — no direct field yet)
        String version,                    // connector_bundle_version.revision
        String displayName,                // integration_method.display_name
        String maintainer,                 // connector.maintainer
        String maintainerOrganization,     // organization.name of the maintainer user (null if none)
        String licenseType,                // connector_bundle.license
        String implementationDescription,  // connector.description
        String browseLink,                 // connector_bundle_version.browse_link
        String ticketingLink,              // connector_bundle.ticketing_link
        String buildFramework,             // connector_bundle_version.build_framework
        String gitCloneUrl,                // connector_bundle_version.git_clone_url
        String pathToProjectDirectory,     // connector_bundle_version.path_to_project
        String className,                  // connector.fully_qualified_class_name
        String bundleDisplayName,          // connector.display_name
        String bundleName,                 // connector_bundle.bundle_name
        String bundleFramework,            // connector_bundle.framework
        String commitTag,                  // connector_bundle_version.commit_tag
        List<ObjectClassCapabilityDto> objectClassCapabilities, // conn_version_capability + items
        String connectorMinVersion,        // integration_method_connector.connector_minversion
        String connectorMaxVersion         // integration_method_connector.connector_maxversion
) {}
