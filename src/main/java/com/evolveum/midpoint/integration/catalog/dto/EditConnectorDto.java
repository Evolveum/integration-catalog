/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import com.evolveum.midpoint.integration.catalog.object.BuildFrameworkType;
import com.evolveum.midpoint.integration.catalog.object.ConnectorBundle;

import java.util.List;

/**
 * Payload for editing an existing connector linked to an integration method revision.
 * The connector is identified by its id in the request path; these values replace the
 * current connector / bundle / bundle-version / version fields in place.
 */
public record EditConnectorDto(
        String displayName,                                          // connector.display_name + connector_bundle.display_name
        String description,                                          // connector.description
        String maintainer,                                           // connector.maintainer
        ConnectorBundle.LicenseType license,                         // connector_bundle.license
        String browseLink,                                           // connector_bundle_version.browse_link
        String supportPortal,                                        // connector_bundle.ticketing_link
        String gitCloneUrl,                                          // connector_bundle_version.git_clone_url
        BuildFrameworkType buildFramework,                           // connector_bundle.build_framework
        String pathToProject,                                        // connector_bundle_version.path_to_project
        String className,                                            // connector.fully_qualified_class_name
        String bundleName,                                           // connector_bundle.bundle_name
        String commitTag,                                            // connector_bundle_version.commit_tag
        List<IntegrationMethodCapabilityGroupDto> connectorCapabilities // conn_version_capability + items (replaced)
) {}
