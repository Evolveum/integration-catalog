/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

/**
 * An external system the catalog performs operations against, and which can therefore be
 * temporarily unreachable.
 */
public enum ExternalSystem {

    /**
     * The support portal that holds the review conversation for a submitted integration method.
     * See {@code OpenProjectClient}.
     */
    OPENPROJECT
}
