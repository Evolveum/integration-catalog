/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

public record CapabilityDto(
        String name,         // capability.name
        String globality,    // capability.globality
        Integer displayOrder // capability.display_order
) {}
