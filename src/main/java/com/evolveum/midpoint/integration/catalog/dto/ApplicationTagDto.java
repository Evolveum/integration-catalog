/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import lombok.Getter;

@Getter
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

}
