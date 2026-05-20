/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

public record CountryOfOriginDto(
        Long id,            // country_of_origin.id
        String name,        // country_of_origin.name
        String displayName  // country_of_origin.display_name
) {}
