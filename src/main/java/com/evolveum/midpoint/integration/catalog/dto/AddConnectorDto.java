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
 * Payload for adding a connector to an existing integration method revision.
 * When {@code existingConnectorId} is set, that catalog connector is linked directly;
 * otherwise a new connector (with its bundle and version) is created.
 */
public record AddConnectorDto(
        Integer existingConnectorId,                                 // connector.id (when linking an existing connector)
        String displayName,                                          // connector.display_name
        String description,                                          // connector.description
        String maintainer,                                           // connector.maintainer
        ConnectorBundle.FrameworkType framework,                     // connector_bundle.framework
        ConnectorBundle.LicenseType license,                         // connector_bundle.license
        String browseLink,                                           // connector_bundle_version.browse_link
        String gitCloneUrl,                                          // connector_bundle_version.git_clone_url
        BuildFrameworkType buildFramework,                           // connector_bundle.build_framework
        String pathToProject,                                        // connector_bundle_version.path_to_project
        String className,                                            // connector.fully_qualified_class_name
        String bundleName,                                           // connector_bundle.bundle_name
        String version,                                              // connector_bundle_version.bundle_version
        String commitTag,                                            // connector_bundle_version.commit_tag
        Integer midpointMinVersion,                                  // integration_method.midpoint_minversion
        Integer midpointMaxVersion,                                  // integration_method.midpoint_maxversion
        String connectorVersionFrom,                                 // integration_method_connector.connector_minversion
        String connectorVersionTo,                                   // integration_method_connector.connector_maxversion
        List<IntegrationMethodCapabilityGroupDto> connectorCapabilities // conn_version_capability + items
) {}
