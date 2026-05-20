/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

export interface ImplementationListItem {
  id: string;                     // integration_method.id
  name: string;                   // integration_method.display_name
  description: string;            // integration_method.description
  publishedDate: string;          // connector_bundle_version.released_date (null — no direct field yet)
  version: string;                // connector_bundle_version.bundle_version
  displayName: string;            // integration_method.display_name
  maintainer: string;             // connector_bundle.maintainer
  licenseType: string;            // connector_bundle.license
  implementationDescription: string; // integration_method.description
  browseLink: string;             // connector_bundle_version.browse_link
  ticketingLink: string;          // connector_bundle.ticketing_link
  buildFramework: string;         // connector_bundle_version.build_framework
  gitCloneUrl: string;            // connector_bundle_version.git_clone_url
  pathToProjectDirectory: string; // connector_bundle_version.path_to_project
  className: string;              // connector.fully_qualified_class_name
  bundleDisplayName: string;      // connector_bundle.display_name
  bundleFramework: string;        // connector_bundle.framework
}
