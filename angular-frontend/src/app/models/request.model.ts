/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

export interface Request {
  id?: number;               // request.id
  applicationId: string;     // request.application_id (FK → application.id)
  capabilitiesType: string;  // request context field
  requester: string;         // request.requester
}

export interface CreateRequest {
  applicationId: string;
  capabilitiesType: string;
  requester: string;
}

export interface ObjectClassCapabilityEntry {
  objectName: string;
  capabilities: string[];
}

/**
 * Request payload for submitting an integration request
 */
export interface IntegrationRequest {
  integrationApplicationName: string;
  integrationMethod: string;
  deploymentType: string;
  capabilities: ObjectClassCapabilityEntry[];
  description: string;
  systemVersion: string;
  contactEmail: string;
  openToCollaborate: boolean;
  requester: string | null;
}

/**
 * Tag with type information for upload payload
 */
export interface TagWithType {
  name: string;
  tagType: 'CATEGORY' | 'DEPLOYMENT';
}

/**
 * Application data for upload payload
 */
export interface UploadApplicationData {
  id: string | null;   // application.id (null for new)
  displayName: string; // application.display_name
  description: string; // application.description
  logo: string | null; // application.logo_path (base64 encoded)
  origins: string[];   // country_of_origin.name
  tags: TagWithType[]; // application_tag entries via application_application_tag
}

/**
 * Integration method data for upload payload
 */
export interface UploadIntegrationMethodData {
  id: string | null;              // integration_method.id (null for new)
  displayName: string;            // integration_method.display_name
  revision: string;               // integration_method.revision
  description: string;            // integration_method.description
  tutorial: string;               // integration_method.tutorial
  typeIds: number[];              // integration_method_type.id
  midpointMinVersion: number | null; // midpoint_version.id (FK for min version)
  midpointMaxVersion: number | null; // midpoint_version.id (FK for max version)
}

/**
 * Connector data for upload payload
 */
export interface UploadConnectorData {
  displayName: string;               // connector.display_name
  description: string;               // connector.description
  maintainer: string;                // connector.maintainer
  framework: string;                 // connector_bundle.framework
  license: string | null;            // connector_bundle.license
  ticketingSystemLink: string | null; // connector_bundle.ticketing_link
  browseLink: string | null;         // connector_bundle_version.browse_link
  gitCloneUrl: string | null;        // connector_bundle_version.git_clone_url
  buildFramework: string | null;     // connector_bundle.build_framework
  pathToProject: string | null;      // connector_bundle_version.path_to_project
  className: string | null;          // connector.fully_qualified_class_name
  bundleName: string | null;         // connector_bundle.bundle_name
  version: string | null;            // connector_bundle_version.bundle_version
  commitTag: string | null;          // connector_bundle_version.commit_tag
  bundleDisplayName: string | null;  // connector_bundle.display_name
}

/**
 * File item for upload payload
 */
export interface UploadFileItem {
  path: string;
  content: string;
}

/**
 * Capability group (one object class with its selected capabilities)
 */
export interface IntegrationMethodCapabilityGroup {
  objectClass: string;          // object_class_capabilities.object_name
  capabilityNames: string[];    // capability.name via integration_method_capability_item / conn_version_capability_item
}

/**
 * Complete payload for uploading a connector
 */
export interface UploadConnectorPayload {
  application: UploadApplicationData;
  integrationMethod: UploadIntegrationMethodData;
  connector: UploadConnectorData;
  files: UploadFileItem[];
  integrationMethodCapabilities: IntegrationMethodCapabilityGroup[];
  connectorCapabilities: IntegrationMethodCapabilityGroup[];
}

/**
 * Form data emitted from PublishFormImpl component
 */
export interface ImplementationFormData {
  isNewVersion: boolean | null;
  isEditingVersion: boolean;
  selectedImplementation: import('./implementation-list-item.model').ImplementationListItem | null;
  displayName: string;
  maintainer: string;
  licenseType: string;
  implementationDescription: string;
  browseLink: string;
  ticketingLink: string;
  buildFramework: string;
  gitCloneUrl: string;
  pathToProjectDirectory: string;
  className: string;
  uploadedFile: UploadedFile | null;
}

/**
 * Uploaded file structure
 */
export interface UploadedFile {
  name: string;
  data: string;
}
