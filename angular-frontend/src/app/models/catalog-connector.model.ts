export interface CatalogConnectorObjectClassCapability {
  objectName: string;          // conn_version_capability.object_class
  capabilities: string[];      // capability.name items
}

export interface CatalogConnector {
  id: number;
  displayName: string;       // connector.display_name
  description: string;       // connector.description
  version: string;           // connector.revision
  bundleDisplayName: string; // connector_bundle.display_name
  maintainer: string;        // connector.maintainer
  licenseType: string;       // connector_bundle.license
  buildFramework: string;    // connector_bundle.build_framework
  bundleFramework: string;   // connector_bundle.framework
  browseLink: string;        // latest connector_bundle_version.browse_link
  gitCloneUrl: string;       // latest connector_bundle_version.git_clone_url
  pathToProject: string;     // latest connector_bundle_version.path_to_project
  className: string;         // connector.fully_qualified_class_name
  objectClassCapabilities: CatalogConnectorObjectClassCapability[]; // conn_version_capability + items
}
