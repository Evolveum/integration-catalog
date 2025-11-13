/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

public record ApplicationTagDto(
        Long id,
        String name,
        String displayName,
        String tagType
) {}
