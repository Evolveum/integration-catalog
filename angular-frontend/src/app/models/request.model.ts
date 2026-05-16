/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

export interface Request {
  id?: number;
  applicationId: string;
  capabilitiesType: string;
  requester: string;
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
  id: string | null;
  displayName: string;
  description: string;
  logo: string | null;
  origins: string[];
  tags: TagWithType[];
}

/**
 * Integration method data for upload payload
 */
export interface UploadIntegrationMethodData {
  id: string | null;
  displayName: string;
  revision: string;
  description: string;
  tutorial: string;
  typeIds: number[];
  midpointMinVersion: number | null;
  midpointMaxVersion: number | null;
}

/**
 * Connector data for upload payload
 */
export interface UploadConnectorData {
  displayName: string;
  description: string;
  maintainer: string;
  framework: string;
  license: string | null;
  ticketingSystemLink: string | null;
  browseLink: string | null;
  gitCloneUrl: string | null;
  buildFramework: string | null;
  pathToProject: string | null;
  className: string | null;
  bundleName: string | null;
  version: string | null;
  commitTag: string | null;
  bundleDisplayName: string | null;
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
  objectClass: string;
  capabilityNames: string[];
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
