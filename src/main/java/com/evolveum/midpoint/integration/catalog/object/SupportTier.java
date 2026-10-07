/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

/**
 * Support tier of a connector, set by hand by a superuser and shown on every method using it.
 * Declared from the lowest tier up: a subscription to a tier covers that tier and every one below it.
 * Stored as the connector tag named {@link #tagName()}.
 */
public enum SupportTier {
    STANDARD("supp_tier_standard"),
    ADVANCED("supp_tier_advanced"),
    PREMIUM("supp_tier_premium");

    private final String tagName;

    SupportTier(String tagName) {
        this.tagName = tagName;
    }

    /** connector_tag.name of the tag holding this tier. */
    public String tagName() {
        return tagName;
    }

    /** The tier a connector_tag name stands for; null for an ordinary tag. */
    public static SupportTier fromTagName(String tagName) {
        for (SupportTier tier : values()) {
            if (tier.tagName.equals(tagName)) {
                return tier;
            }
        }
        return null;
    }
}
