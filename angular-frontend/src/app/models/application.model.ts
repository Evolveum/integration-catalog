/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

export interface ApplicationTag {
  id: number;             // application_tag.id
  name: string;           // application_tag.name
  displayName: string;    // application_tag.display_name
  tagType: string | null; // application_tag.tag_type
}

export interface CountryOfOrigin {
  id: number;           // country_of_origin.id
  name: string;         // country_of_origin.name
  displayName: string;  // country_of_origin.display_name
}

export interface Application {
  id: string;                              // application.id
  displayName: string;                     // application.display_name
  description: string;                     // application.description
  logoPath: string | null;                 // application.logo_path
  lifecycleState: string | null;           // application.lifecycle_state
  origins: CountryOfOrigin[] | null;       // country_of_origin via application_origin
  categories: ApplicationTag[] | null;     // application_tag (CATEGORY) via application_application_tag
  tags: ApplicationTag[] | null;           // application_tag via application_application_tag
  capabilities?: string[] | null;          // capability.name via integration_method_capability
  pendingRequest?: boolean;                // derived: lifecycleState == REQUESTED
  requestId?: number | null;               // request.id
  voteCount?: number;                      // computed: count of vote rows
  frameworks?: string[] | null;            // connector_bundle.framework
  midpointVersions?: string[] | null;      // midpoint_version.version
  currentMidpointVersion?: string | null;  // midpoint_version.version (where is_current = true)
}

export function hasLogo(app: Application): boolean {
  return !!app.logoPath;
}

export function getLogoUrl(appId: string, apiUrl: string): string {
  return `${apiUrl}/applications/${appId}/logo`;
}
