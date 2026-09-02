/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.util.List;

public record CatalogConnectorDto(
        Integer id,               // connector_bundle.id  (identifies the bundle in the catalog list only)
        Integer connectorId,      // connector.id  (sent as existingConnectorId, to link this very connector)
        String displayName,       // connector.display_name
        String description,       // connector.description
        String version,           // connector.revision
        String bundleDisplayName, // connector_bundle.display_name
        String maintainer,        // connector.maintainer
        String licenseType,       // connector_bundle.license
        String buildFramework,    // latest connector_bundle_version.build_framework
        String bundleFramework,   // connector_bundle.framework
        String projectHomepage,   // connector_bundle.project_homepage
        String branchUrl,         // latest connector_bundle_version.browse_link
        String gitCloneUrl,       // connector_bundle.git_clone_ulr
        String pathToProject,     // latest connector_bundle_version.path_to_project
        String className,         // connector.fully_qualified_class_name
        List<ObjectClassCapabilityDto> objectClassCapabilities // conn_version_capability + items
) {
}
