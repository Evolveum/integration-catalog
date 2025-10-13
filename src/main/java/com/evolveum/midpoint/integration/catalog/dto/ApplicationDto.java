/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public class ApplicationDto {
    private UUID id;
    private String displayName;
    private String description;
    private byte[] logo;
    private String riskLevel;
    private String lifecycleState;
    private OffsetDateTime lastModified;
    private List<CountryOfOriginDto> origins;
    private List<ApplicationTagDto> categories;
    private List<ApplicationTagDto> tags;
    private List<ImplementationVersionDto> implementationVersions;

    public ApplicationDto(UUID id, String displayName, String description, byte[] logo, String riskLevel, String lifecycleState, OffsetDateTime lastModified, List<CountryOfOriginDto> origins, List<ApplicationTagDto> categories, List<ApplicationTagDto> tags, List<ImplementationVersionDto> implementationVersions) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.logo = logo;
        this.riskLevel = riskLevel;
        this.lifecycleState = lifecycleState;
        this.lastModified = lastModified;
        this.origins = origins;
        this.categories = categories;
        this.tags = tags;
        this.implementationVersions = implementationVersions;
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

    public OffsetDateTime getLastModified() {
        return lastModified;
    }

    public List<CountryOfOriginDto> getOrigins() {
        return origins;
    }

    public List<ApplicationTagDto> getCategories() {
        return categories;
    }

    public List<ApplicationTagDto> getTags() {
        return tags;
    }

    public List<ImplementationVersionDto> getImplementationVersions() {
        return implementationVersions;
    }
}
