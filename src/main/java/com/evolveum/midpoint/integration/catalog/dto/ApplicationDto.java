/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Getter
public class ApplicationDto {
    private UUID id;
    private String displayName;
    private String description;
    private byte[] logo;
    private String riskLevel;
    private String lifecycleState;
    private OffsetDateTime lastModified;
    private OffsetDateTime createdAt;
    private List<String> capabilities;
    private String requester;
    private List<CountryOfOriginDto> origins;
    private List<ApplicationTagDto> categories;
    private List<ApplicationTagDto> tags;
    private List<ImplementationVersionDto> implementationVersions;
    private Long requestId;
    private Long voteCount;

    public ApplicationDto(UUID id, String displayName, String description, byte[] logo, String riskLevel, String lifecycleState, OffsetDateTime lastModified, OffsetDateTime createdAt, List<String> capabilities, String requester, List<CountryOfOriginDto> origins, List<ApplicationTagDto> categories, List<ApplicationTagDto> tags, List<ImplementationVersionDto> implementationVersions, Long requestId, Long voteCount) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.logo = logo;
        this.riskLevel = riskLevel;
        this.lifecycleState = lifecycleState;
        this.lastModified = lastModified;
        this.createdAt = createdAt;
        this.capabilities = capabilities;
        this.requester = requester;
        this.origins = origins;
        this.categories = categories;
        this.tags = tags;
        this.implementationVersions = implementationVersions;
        this.requestId = requestId;
        this.voteCount = voteCount;
    }

}
