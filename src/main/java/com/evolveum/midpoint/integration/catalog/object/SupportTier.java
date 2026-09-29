/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

/**
 * Support tier of an integration method, set by hand by a reviewer. Declared from the lowest tier
 * up: a subscription to a tier covers that tier and every one below it.
 */
public enum SupportTier {
    STANDARD,
    ADVANCED,
    PREMIUM
}
