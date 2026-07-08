/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

export interface ObjectClassCapability {
  objectName: string;             // conn_version_capability.object_class
  capabilities: string[];         // capability.name items
}

export interface ImplementationListItem {
  id: string;                     // integration_method.id
  connectorId: number | null;     // connector.id (identifies the linked connector for editing)
  name: string;                   // integration_method.display_name
  description: string;            // integration_method.description
  publishedDate: string;          // connector_bundle_version.released_date (null — no direct field yet)
  version: string;                // connector_bundle_version.revision
  displayName: string;            // integration_method.display_name
  maintainer: string;             // connector.maintainer
  licenseType: string;            // connector_bundle.license
  implementationDescription: string; // connector.description
  browseLink: string;             // connector_bundle_version.browse_link
  ticketingLink: string;          // connector_bundle.ticketing_link
  buildFramework: string;         // connector_bundle_version.build_framework
  gitCloneUrl: string;            // connector_bundle_version.git_clone_url
  pathToProjectDirectory: string; // connector_bundle_version.path_to_project
  className: string;              // connector.fully_qualified_class_name
  bundleDisplayName: string;      // connector.display_name
  bundleName: string;             // connector_bundle.bundle_name
  bundleFramework: string;        // connector_bundle.framework
  commitTag: string;              // connector_bundle_version.commit_tag
  objectClassCapabilities: ObjectClassCapability[]; // conn_version_capability + items
  connectorMinVersion: string | null; // integration_method_connector.connector_minversion
  connectorMaxVersion: string | null; // integration_method_connector.connector_maxversion
}
