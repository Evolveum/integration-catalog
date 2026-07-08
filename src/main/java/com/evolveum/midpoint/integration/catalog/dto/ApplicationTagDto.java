/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

public record ApplicationTagDto(
        Long id,            // application_tag.id
        String name,        // application_tag.name
        String displayName, // application_tag.display_name
        String tagType      // application_tag.tag_type
) {}
