/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

/**
 * DTO representing the mutable metadata of an application that may be changed
 * by a {@code PATCH /api/applications/{id}} request.
 *
 * Only superusers are allowed to submit updates. The logo is not part of this
 * DTO and must be uploaded separately via {@code POST /api/applications/{id}/logo}.
 */

public record UpdateApplicationDto(
        String displayName,
        String description
) {}