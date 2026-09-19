/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import com.evolveum.midpoint.integration.catalog.object.MaintainerType;

/**
 * A maintainer over the wire, in both directions. Which of {@code username} and
 * {@code organizationName} is filled follows the category, as on the row itself.
 */
public record MaintainerDto(
        Long id,
        String username,
        String organizationName,
        MaintainerType category,
        String label
) {}
