/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.dto;

public class CountryOfOriginDto {
    private Long id;
    private String name;
    private String displayName;

    public CountryOfOriginDto(Long id, String name, String displayName) {
        this.id = id;
        this.name = name;
        this.displayName = displayName;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }
}
