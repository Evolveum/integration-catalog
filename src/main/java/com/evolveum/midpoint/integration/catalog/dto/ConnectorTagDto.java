/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

public record ConnectorTagDto(
        String name,        // connector_tag.name
        String displayName  // connector_tag.display_name
) {}
