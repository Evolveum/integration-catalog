/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

public record CatalogConnectorDto(
        Integer id,               // connector_bundle.id  (sent as connectorBundleId to backend)
        String displayName,       // connector.display_name
        String description,       // connector.description
        String version,           // connector.revision
        String bundleDisplayName, // connector_bundle.display_name
        String maintainer,        // connector.maintainer
        String licenseType,       // connector_bundle.license
        String buildFramework,    // connector_bundle.build_framework
        String bundleFramework,   // connector_bundle.framework
        String browseLink,        // latest connector_bundle_version.browse_link
        String gitCloneUrl,       // latest connector_bundle_version.git_clone_ulr
        String pathToProject,     // latest connector_bundle_version.path_to_project
        String className          // connector.fully_qualified_class_name
) {
}
