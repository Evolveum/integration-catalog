/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

public record CategoryCountDto(
        String displayName, // application_tag.display_name (where tag_type = CATEGORY)
        Long count          // computed: count of application_application_tag rows
) {}
