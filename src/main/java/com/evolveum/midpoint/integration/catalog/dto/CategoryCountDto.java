/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import lombok.Getter;

@Getter
public class CategoryCountDto {
    private String displayName;
    private Long count;

    public CategoryCountDto(String displayName, Long count) {
        this.displayName = displayName;
        this.count = count;
    }

}
