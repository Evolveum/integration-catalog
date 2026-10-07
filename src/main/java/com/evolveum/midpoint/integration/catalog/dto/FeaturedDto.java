/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

/** Body of {@code PUT /api/applications/{id}/featured}. */
public record FeaturedDto(
        boolean featured
) {}
