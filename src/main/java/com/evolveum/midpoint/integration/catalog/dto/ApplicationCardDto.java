/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.util.UUID;

/**
 * DTO for application card display in list view
 */
public class ApplicationCardDto {
    private UUID id;
    private String displayName;
    private String description;
    private byte[] logo;
    private String riskLevel;
    private String lifecycleState;

    public ApplicationCardDto() {
    }

    public ApplicationCardDto(UUID id, String displayName, String description, byte[] logo, String riskLevel, String lifecycleState) {
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

    public void setId(UUID id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public byte[] getLogo() {
        return logo;
    }

    public void setLogo(byte[] logo) {
        this.logo = logo;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }
}
