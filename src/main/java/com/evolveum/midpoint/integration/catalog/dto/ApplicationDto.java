/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.util.UUID;

public class ApplicationDto {
    private UUID id;
    private String displayName;
    private String description;
    private byte[] logo;
    private String riskLevel;
    private String lifecycleState;

    public ApplicationDto(UUID id, String displayName, String description, byte[] logo, String riskLevel, String lifecycleState) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.logo = logo;
        this.riskLevel = riskLevel;
        this.lifecycleState = lifecycleState;
    }

    public UUID getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public byte[] getLogo() {
        return logo;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }
}
