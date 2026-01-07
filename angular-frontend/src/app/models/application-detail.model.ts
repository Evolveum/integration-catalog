/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

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

export interface ImplementationVersion {
  id: string;
  description: string | null;
  implementationTags: string[] | null;
  capabilities: string[] | null;
  connectorVersion: string | null;
  systemVersion: string | null;
  releasedDate: string | null;
  author: string | null;
  lifecycleState: string | null;
  downloadLink: string | null;
  framework: string | null;
  errorMessage: string | null;
  downloadCount: number | null;
  midpointVersion: string | null;
}

export interface ApplicationDetail {
  id: string;
  displayName: string;
  description: string;
  logo: string | null; // Legacy base64 field (deprecated)
  logoPath: string | null;
  logoContentType: string | null;
  logoOriginalName: string | null;
  logoSizeBytes: number | null;
  lifecycleState: string;
  lastModified: string;
  createdAt: string | null;
  capabilities: string[] | null;
  requester: string | null;
  origins: CountryOfOrigin[] | null;
  categories: ApplicationTag[] | null;
  tags: ApplicationTag[] | null;
  implementationVersions: ImplementationVersion[] | null;
}

/**
 * Helper function to check if application detail has a logo
 */
export function hasLogoDetail(app: ApplicationDetail): boolean {
  return !!(app.logoPath || (app.logo && app.logo.length > 0));
}
