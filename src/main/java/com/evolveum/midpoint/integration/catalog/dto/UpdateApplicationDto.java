/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import com.evolveum.midpoint.integration.catalog.object.Application;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * DTO representing the mutable metadata of an application that may be changed
 * by a {@code PATCH /api/applications/{id}} request.
 *
 * Only superusers are allowed to submit updates. The logo is not part of this
 * DTO and must be uploaded separately via {@code POST /api/applications/{id}/logo}.
 */

public record UpdateApplicationDto(
        @Size(max = Application.DISPLAY_NAME_MAX,
                message = "Display name can be at most " + Application.DISPLAY_NAME_MAX + " characters.")
        String displayName,
        @Size(max = Application.DESCRIPTION_MAX,
                message = "Description can be at most " + Application.DESCRIPTION_MAX + " characters.")
        String description,
        List<@Valid ApplicationVersionDto> versions   // the whole new list: ids kept, null ids added, the rest removed; null leaves it as is
) {}