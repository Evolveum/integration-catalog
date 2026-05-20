/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

public enum CapabilityType {
    CREATE,
    GET,
    UPDATE,
    DELETE,
    TEST,
    SCRIPT_ON_CONNECTOR,
    SCRIPT_ON_RESOURCE,
    AUTHENTICATION,
    SEARCH,
    VALIDATE,
    SYNC,
    LIVE_SYNC,
    SCHEMA,
    DISCOVER_CONFIGURATION,
    RESOLVE_USERNAME,
    PARTIAL_SCHEMA,
    COMPLEX_UPDATE_DELTA,
    UPDATE_DELTA
}
