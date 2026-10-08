/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import com.fasterxml.jackson.annotation.JsonAlias;

public enum CapabilityType {
    CREATE(false),
    // Renamed from GET; the alias keeps a build reporting the old name working.
    @JsonAlias("GET")
    READ(false),
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
    UPDATE_DELTA(false),
    PASSWORD(false),
    ACTIVATION(false),
    ASSOCIATIONS(false);


    private final boolean isGlobal;
    CapabilityType(boolean isGlobal) {

        this.isGlobal = isGlobal;
    }

    public boolean isGlobal() {
        return isGlobal;
    }
}
