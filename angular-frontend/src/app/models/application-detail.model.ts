/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

export interface MidpointVersion {
  id: number;
  version: string;
  versionName: string;
}

export interface CountryOfOrigin {
  id: number;
  name: string;
  displayName: string;
}

export interface ApplicationTag {
  id: number;
  name: string;
  displayName: string;
  tagType: string | null;
}

export interface IntegrationMethod {
  id: string;
  description: string | null;
  implementationTags: string[] | null;
  capabilities: string[] | null;
  connectorVersion: string | null;
  systemVersion: string | null;
  releasedDate: string | null;
  author: string | null;
  organizationId: number | null;
  lifecycleState: string | null;
  downloadLink: string | null;
  framework: string | null;
  errorMessage: string | null;
  downloadCount: number | null;
  midpointMinVersionId: number | null;
  midpointMaxVersionId: number | null;
  connectorDisplayName: string | null;
  integMethodTypes: string[] | null;
  objectClassCapabilities: ObjectClassCapability[] | null;
  revision: string | null;
}

export interface ObjectClassCapability {
  objectName: string;
  capabilities: string[];
}

export interface ApplicationDetail {
  id: string;
  displayName: string;
  description: string;
  logoPath: string | null;
  lifecycleState: string;
  updated: string | null;
  createdAt: string | null;
  capabilities: string[] | null;
  requester: string | null;
  origins: CountryOfOrigin[] | null;
  categories: ApplicationTag[] | null;
  tags: ApplicationTag[] | null;
  integrationMethods: IntegrationMethod[] | null;
  requestId: number | null;
  voteCount: number | null;
  frameworks: string[] | null;
  objectClassCapabilities: ObjectClassCapability[] | null;
}

export function hasLogoDetail(app: ApplicationDetail): boolean {
  return !!app.logoPath;
}
