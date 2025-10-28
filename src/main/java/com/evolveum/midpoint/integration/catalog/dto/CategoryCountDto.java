/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.dto;

public class CategoryCountDto {
    private String displayName;
    private Long count;

    public CategoryCountDto(String displayName, Long count) {
        this.displayName = displayName;
        this.count = count;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Long getCount() {
        return count;
    }
}
