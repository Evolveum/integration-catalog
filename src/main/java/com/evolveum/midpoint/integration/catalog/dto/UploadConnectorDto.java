/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import com.evolveum.midpoint.integration.catalog.object.BuildFrameworkType;
import com.evolveum.midpoint.integration.catalog.object.ConnectorBundle;

/**
 * The connector half of a publish. Describes a connector to create, unless
 * {@code existingConnectorId} names one already in the catalog - then that connector is linked as it
 * is and every other field here is ignored, because the publish form disables them all in that case.
 */
public record UploadConnectorDto(
        String displayName,                      // connector.display_name
        ConnectorBundle.FrameworkType framework,  // connector_bundle.framework
        String version,                          // connector_bundle_version.bundle_version
        ConnectorBundle.LicenseType license,     // connector_bundle.license
        BuildFrameworkType buildFramework,        // connector_bundle.build_framework
        String description,                      // connector.description
        String maintainer,                       // connector.maintainer
        String browseLink,                       // connector_bundle_version.browse_link
        String ticketingSystemLink,              // connector_bundle.ticketing_link
        String gitCloneUrl,                      // connector_bundle_version.git_clone_url
        String className,                        // connector.fully_qualified_class_name
        String pathToProject,                    // connector_bundle_version.path_to_project
        String commitTag,                        // connector_bundle_version.commit_tag
        String bundleDisplayName,                // connector_bundle.display_name (the form's "bundle name")
        Integer connectorBundleId,               // connector_bundle.id (when reusing existing bundle)
        Integer existingConnectorId              // connector.id (when linking a connector already published)
) {
}
