/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

export type MaintainerCategory = 'USER' | 'ORG' | 'EVOLVEUM' | 'COMMUNITY';

export interface Maintainer {
  id: number | null;                // maintainers.id; null until the row is created
  username: string | null;          // maintainers.username (USER only)
  organizationName: string | null;  // organizations.name, the alias (ORG only)
  category: MaintainerCategory;     // maintainers.category
  label: string | null;             // what to show for it; the server fills it in
}

/** The maintainer of a person, as the maintainer combobox posts one that has no row yet. */
export function userMaintainer(username: string): Maintainer {
  return { id: null, username, organizationName: null, category: 'USER', label: username };
}

/** The maintainer of an organization, named by its alias and shown under its display name. */
export function organizationMaintainer(alias: string, label: string | null): Maintainer {
  return { id: null, username: null, organizationName: alias, category: 'ORG', label: label ?? alias };
}

/** What to show for a maintainer, whatever the server sent. */
export function maintainerLabel(maintainer: Maintainer | null | undefined): string {
  if (!maintainer) return '';
  return maintainer.label
      ?? maintainer.username
      ?? maintainer.organizationName
      ?? (maintainer.category === 'EVOLVEUM' ? 'Evolveum' : 'Community');
}

/** Whether two options stand for the same maintainer, id or not. */
export function sameMaintainer(a: Maintainer | null, b: Maintainer | null): boolean {
  if (!a || !b) return a === b;
  if (a.id !== null && b.id !== null) return a.id === b.id;
  if (a.category !== b.category) return false;
  const key = (m: Maintainer) => (m.username ?? m.organizationName ?? '').toLowerCase();
  return key(a) === key(b);
}
