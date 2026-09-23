/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

/**
 * Whether an integration method supports a capability on an object. UNKNOWN is a state of its own:
 * nobody determined it, which is not the same as a confirmed NO.
 */
public enum CapabilityState {
    YES,
    NO,
    UNKNOWN
}
