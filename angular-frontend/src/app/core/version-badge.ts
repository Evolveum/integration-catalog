/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

/** The version badge ("v1", "v2", …) shows only the major part of a revision. */
export function versionBadge(revision: string | null | undefined): string {
  const major = parseInt((revision ?? '').split('.')[0], 10);
  return isNaN(major) ? 'v1' : `v${major}`;
}
