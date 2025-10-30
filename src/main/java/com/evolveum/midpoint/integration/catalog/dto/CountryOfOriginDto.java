/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import lombok.Getter;

@Getter
public class CountryOfOriginDto {
    private Long id;
    private String name;
    private String displayName;

    public CountryOfOriginDto(Long id, String name, String displayName) {
        this.id = id;
        this.name = name;
        this.displayName = displayName;
    }

}
