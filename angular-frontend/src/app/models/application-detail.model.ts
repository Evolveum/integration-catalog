/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

export interface MidpointVersion {
  id: number;           // midpoint_version.id
  version: string;      // midpoint_version.version
  versionName: string;  // midpoint_version.version_name
  isCurrent: boolean;   // midpoint_version.is_current
}

export interface CountryOfOrigin {
  id: number;           // country_of_origin.id
  name: string;         // country_of_origin.name
  displayName: string;  // country_of_origin.display_name
}

export interface ApplicationTag {
  id: number;             // application_tag.id
  name: string;           // application_tag.name
  displayName: string;    // application_tag.display_name
  tagType: string | null; // application_tag.tag_type
}

export interface IntegrationMethod {
  id: string;                              // integration_method.id
  description: string | null;              // integration_method.description
  implementationTags: string[] | null;     // integration_method_type.name
  capabilities: string[] | null;           // capability.name via integration_method_capability
  connectorVersion: string | null;         // connector_bundle_version.bundle_version
  systemVersion: string | null;            // integration_method.system_version
  releasedDate: string | null;             // connector_bundle_version.released_date
  author: string | null;                   // connector.author
  organizationId: number | null;           // connector.organization_id
  lifecycleState: string | null;           // integration_method.lifecycle_state
  downloadLink: string | null;             // generated download URL
  framework: string | null;               // connector_bundle.framework
  errorMessage: string | null;             // integration_method.error_message
  downloadCount: number | null;            // computed: count of download rows
  midpointMinVersionId: number | null;     // integration_method.midpoint_min_version_id (FK → midpoint_version.id)
  midpointMaxVersionId: number | null;     // integration_method.midpoint_max_version_id (FK → midpoint_version.id)
  connectorDisplayName: string | null;     // connector.display_name
  integMethodTypes: string[] | null;       // integration_method_type.name
  objectClassCapabilities: ObjectClassCapability[] | null; // object_class_capabilities
  revision: string | null;                // integration_method.revision
}

export interface ObjectClassCapability {
  objectName: string;     // object_class_capabilities.object_name
  capabilities: string[]; // capability.name items
}

export interface ApplicationDetail {
  id: string;                               // application.id
  displayName: string;                      // application.display_name
  description: string;                      // application.description
  logoPath: string | null;                  // application.logo_path
  lifecycleState: string;                   // application.lifecycle_state
  updated: string | null;                   // application.updated
  createdAt: string | null;                 // application.created_at
  capabilities: string[] | null;            // capability.name via integration_method_capability
  requester: string | null;                 // request.requester
  origins: CountryOfOrigin[] | null;        // country_of_origin via application_origin
  categories: ApplicationTag[] | null;      // application_tag (CATEGORY) via application_application_tag
  tags: ApplicationTag[] | null;            // application_tag via application_application_tag
  integrationMethods: IntegrationMethod[] | null; // integration_method
  requestId: number | null;                 // request.id
  voteCount: number | null;                 // computed: count of vote rows
  frameworks: string[] | null;              // connector_bundle.framework
  objectClassCapabilities: ObjectClassCapability[] | null; // object_class_capabilities
}

export function hasLogoDetail(app: ApplicationDetail): boolean {
  return !!app.logoPath;
}
