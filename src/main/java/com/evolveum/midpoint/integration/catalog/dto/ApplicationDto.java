/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.dto;

public class ApplicationDto {
    private String displayName;
    private String description;
    private byte[] logo;

    public ApplicationDto(String displayName, String description, byte[] logo) {
        this.displayName = displayName;
        this.description = description;
        this.logo = logo;
    }
}
