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
 * Payload for editing an existing connector linked to an integration method revision. The connector is
 * identified by its id in the request path.
 */
public record EditConnectorDto(
        // -- metadata ------------------------------------------------------------------------------
        String displayName,                                          // connector.display_name
        String description,                                          // connector.description
        String maintainer,                                           // connector.maintainer
        String projectHomepage,                                      // connector_bundle.project_homepage
        String supportPortal,                                        // connector_bundle.ticketing_link
        String bundleDisplayName,                                    // connector_bundle.display_name (the form's "connector bundle name")
        // -- build data ----------------------------------------------------------------------------
        String version,                                              // connector_bundle_version.bundle_version + connector.revision
        String branchUrl,                                            // connector_bundle_version.browse_link (Jenkins BRANCH_URL)
        String commitTag,                                            // connector_bundle_version.commit_tag
        String pathToProject,                                        // connector_bundle_version.path_to_project
        BuildFrameworkType buildFramework,                           // connector_bundle_version.build_framework
        String className,                                            // connector_version.fully_qualified_class_name
        List<IntegrationMethodCapabilityGroupDto> connectorCapabilities, // conn_version_capability + items (replaced)
        // -- fixed after the first version ---------------------------------------------------------
        ConnectorBundle.LicenseType license,                         // connector_bundle.license
        String gitCloneUrl                                           // connector_bundle.git_clone_ulr
) {}
