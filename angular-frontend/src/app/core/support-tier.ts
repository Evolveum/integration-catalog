/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

/** integration_method.support_tier; null means the method has no tier yet. */
export type SupportTier = 'STANDARD' | 'ADVANCED' | 'PREMIUM';

/** Lowest first: a subscription to a tier covers that tier and every one before it. */
export const SUPPORT_TIERS: { value: SupportTier; label: string }[] = [
  { value: 'STANDARD', label: 'Standard' },
  { value: 'ADVANCED', label: 'Advanced' },
  { value: 'PREMIUM', label: 'Premium' }
];

export function supportTierLabel(tier: SupportTier | null | undefined): string {
  return SUPPORT_TIERS.find(t => t.value === tier)?.label ?? '';
}

/** Bootstrap color classes for a tier's badge or select, one place for every page; '' when untiered. */
export function supportTierClasses(tier: SupportTier | null | undefined): string {
  switch (tier) {
    case 'STANDARD': return 'bg-primary-subtle text-primary-emphasis border-primary-subtle';
    case 'ADVANCED': return 'bg-success-subtle text-success-emphasis border-success-subtle';
    case 'PREMIUM': return 'bg-warning-subtle text-warning-emphasis border-warning-subtle';
    default: return '';
  }
}

/** Whether a subscription to {@code subscription} covers a method of tier {@code tier}. */
export function isCoveredBy(tier: SupportTier | null | undefined, subscription: SupportTier): boolean {
  const rank = (t: SupportTier) => SUPPORT_TIERS.findIndex(s => s.value === t);
  return !!tier && rank(tier) <= rank(subscription);
}
