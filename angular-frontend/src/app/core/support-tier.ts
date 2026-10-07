/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

/** A connector's support tier, held as a connector tag; null means no tier yet. */
export type SupportTier = 'STANDARD' | 'ADVANCED' | 'PREMIUM';

/** Lowest first: a subscription to a tier covers that tier and every one before it. tagName = connector_tag.name holding it. */
export const SUPPORT_TIERS: { value: SupportTier; label: string; tagName: string }[] = [
  { value: 'STANDARD', label: 'Standard', tagName: 'supp_tier_standard' },
  { value: 'ADVANCED', label: 'Advanced', tagName: 'supp_tier_advanced' },
  { value: 'PREMIUM', label: 'Premium', tagName: 'supp_tier_premium' }
];

export function supportTierLabel(tier: SupportTier | null | undefined): string {
  return SUPPORT_TIERS.find(t => t.value === tier)?.label ?? '';
}

/** The tier a connector_tag name stands for; null for an ordinary tag. */
export function supportTierOfTag(tagName: string): SupportTier | null {
  return SUPPORT_TIERS.find(t => t.tagName === tagName)?.value ?? null;
}

/** Whether a subscription to {@code subscription} covers a method of tier {@code tier}. */
export function isCoveredBy(tier: SupportTier | null | undefined, subscription: SupportTier): boolean {
  const rank = (t: SupportTier) => SUPPORT_TIERS.findIndex(s => s.value === t);
  return !!tier && rank(tier) <= rank(subscription);
}
