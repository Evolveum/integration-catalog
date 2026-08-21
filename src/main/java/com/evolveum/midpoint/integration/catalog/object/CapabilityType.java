/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

public enum CapabilityType {
    CREATE(false),
    GET(false),
    UPDATE(false),
    DELETE(false),
    TEST(true),
    SCRIPT_ON_CONNECTOR(true),
    SCRIPT_ON_RESOURCE(true),
    AUTHENTICATION(true),
    SEARCH(false),
    VALIDATE(false),
    SYNC(false),
    LIVE_SYNC(false),
    SCHEMA(true),
    DISCOVER_CONFIGURATION(true),
    RESOLVE_USERNAME(true),
    PARTIAL_SCHEMA(true),
    COMPLEX_UPDATE_DELTA(false),
    UPDATE_DELTA(false);


    private final boolean isGlobal;
    CapabilityType(boolean isGlobal) {

        this.isGlobal = isGlobal;
    }

    public boolean isGlobal() {
        return isGlobal;
    }
}
