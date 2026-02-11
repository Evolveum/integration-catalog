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

/**
 * Request payload for submitting an integration request
 */
export interface IntegrationRequest {
  integrationApplicationName: string;
  deploymentType: string;
  capabilities: string[];
  description: string;
  systemVersion: string;
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
 * Implementation data for upload payload
 */
export interface UploadImplementationData {
  implementationId: string | null;
  displayName: string;
  description: string;
  maintainer: string;
  framework: string;
  license: string | null;
  ticketingSystemLink: string | null;
  browseLink: string | null;
  checkoutLink: string | null;
  buildFramework: string | null;
  pathToProject: string | null;
  className: string | null;
  bundleName: string | null;
  connectorVersion: string | null;
  downloadLink: string | null;
  connidVersion: string | null;
}

/**
 * File item for upload payload
 */
export interface UploadFileItem {
  path: string;
  content: string;
}

/**
 * Complete payload for uploading a connector
 */
export interface UploadConnectorPayload {
  application: UploadApplicationData;
  implementation: UploadImplementationData;
  files: UploadFileItem[];
}

/**
 * Form data emitted from UploadFormImpl component
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
  checkoutLink: string;
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
