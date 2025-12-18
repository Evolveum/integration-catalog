/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

export interface ApplicationTag {
  id: number;
  name: string;
  displayName: string;
  tagType: string | null;
}

export interface CountryOfOrigin {
  id: number;
  name: string;
  displayName: string;
}

export interface Application {
  id: string;
  displayName: string;
  description: string;
  logo: string | null; // Legacy base64 field (deprecated)
  logoPath: string | null;
  logoContentType: string | null;
  logoOriginalName: string | null;
  logoSizeBytes: number | null;
  lifecycleState: string | null;
  origins: CountryOfOrigin[] | null;
  categories: ApplicationTag[] | null;
  tags: ApplicationTag[] | null;
  capabilities?: string[] | null;
  pendingRequest?: boolean;
  requestId?: number | null;
  voteCount?: number;
  frameworks?: string[] | null;
  midpointVersions?: string[] | null;
}

/**
 * Helper function to check if application has a logo
 */
export function hasLogo(app: Application): boolean {
  return !!(app.logoPath || (app.logo && app.logo.length > 0));
}

/**
 * Get the logo URL for an application
 */
export function getLogoUrl(appId: string, apiUrl: string): string {
  return `${apiUrl}/applications/${appId}/logo`;
}
