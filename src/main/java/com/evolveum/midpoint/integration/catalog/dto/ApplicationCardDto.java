/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.util.List;
import java.util.UUID;

/**
 * DTO for application card display in list view
 */
public record ApplicationCardDto(
        UUID id,
        String displayName,
        String description,
        byte[] logo,
        String lifecycleState,
        List<CountryOfOriginDto> origins,
        List<ApplicationTagDto> categories,
        List<ApplicationTagDto> tags,
        List<String> capabilities,
        Long requestId,
        Long voteCount
) {}
