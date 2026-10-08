/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

/** One license the catalog accepts, so the UI offers and labels exactly the backend's list. */
public record LicenseTypeDto(
        String name,        // ConnectorBundle.LicenseType constant, what the API sends and stores
        String displayName  // how it is shown
) {}
