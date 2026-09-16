/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

/**
 * Renders a capability constant as the label users see.
 *
 * Sentence case, so only the first word is capitalised: PARTIAL_SCHEMA becomes
 * "Partial schema" and SCRIPT_ON_CONNECTOR becomes "Script on connector".
 * Every screen that shows a capability goes through here, so the filter, the
 * badges and the detail panels cannot drift apart.
 */
export function formatCapabilityLabel(capability: string | null | undefined): string {
  if (!capability) {
    return '';
  }
  const words = capability.replace(/_/g, ' ').toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}
