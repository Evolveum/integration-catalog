/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import com.evolveum.midpoint.integration.catalog.object.SupportTier;

/** Body of {@code PUT /api/all-connectors/{id}/tags}: the superuser-set tags of a connector. */
public record ConnectorTagsDto(
        SupportTier tier,  // null removes it; must be null when obsolete
        boolean obsolete
) {}
