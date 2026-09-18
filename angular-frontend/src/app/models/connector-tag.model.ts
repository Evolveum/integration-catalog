/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

export interface ConnectorTag {
  name: string;        // connector_tag.name
  displayName: string; // connector_tag.display_name
}

/** Legacy connector: still allowed and counted, but the catalog warns about it. */
export const OBSOLETE_CONNECTOR_TAG = 'obsolete';

export function isObsoleteConnector(tags: ConnectorTag[] | null | undefined): boolean {
  return !!tags?.some(t => t.name === OBSOLETE_CONNECTOR_TAG);
}
