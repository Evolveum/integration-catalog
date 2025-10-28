/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.dto;

public class ApplicationTagDto {
    private Long id;
    private String name;
    private String displayName;
    private String tagType;

    public ApplicationTagDto(Long id, String name, String displayName, String tagType) {
        this.id = id;
        this.name = name;
        this.displayName = displayName;
        this.tagType = tagType;
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

    public String getTagType() {
        return tagType;
    }
}
