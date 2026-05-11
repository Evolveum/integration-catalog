/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ApplicationDto(
        UUID id,
        String displayName,
        String description,
        String logoPath,
        String lifecycleState,
        LocalDateTime updated,
        LocalDateTime createdAt,
        List<String> capabilities,
        String requester,
        List<CountryOfOriginDto> origins,
        List<ApplicationTagDto> categories,
        List<ApplicationTagDto> tags,
        List<IntegrationMethodDto> integrationMethods,
        Long requestId,
        Long voteCount,
        List<String> frameworks,
        List<ObjectClassCapabilityDto> objectClassCapabilities
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID id;
        private String displayName;
        private String description;
        private String logoPath;
        private String lifecycleState;
        private LocalDateTime updated;
        private LocalDateTime createdAt;
        private List<String> capabilities;
        private String requester;
        private List<CountryOfOriginDto> origins;
        private List<ApplicationTagDto> categories;
        private List<ApplicationTagDto> tags;
        private List<IntegrationMethodDto> integrationMethods;
        private Long requestId;
        private Long voteCount;
        private List<String> frameworks;
        private List<ObjectClassCapabilityDto> objectClassCapabilities;

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder logoPath(String logoPath) {
            this.logoPath = logoPath;
            return this;
        }

        public Builder lifecycleState(String lifecycleState) {
            this.lifecycleState = lifecycleState;
            return this;
        }

        public Builder updated(LocalDateTime updated) {
            this.updated = updated;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder capabilities(List<String> capabilities) {
            this.capabilities = capabilities;
            return this;
        }

        public Builder requester(String requester) {
            this.requester = requester;
            return this;
        }

        public Builder origins(List<CountryOfOriginDto> origins) {
            this.origins = origins;
            return this;
        }

        public Builder categories(List<ApplicationTagDto> categories) {
            this.categories = categories;
            return this;
        }

        public Builder tags(List<ApplicationTagDto> tags) {
            this.tags = tags;
            return this;
        }

        public Builder integrationMethods(List<IntegrationMethodDto> integrationMethods) {
            this.integrationMethods = integrationMethods;
            return this;
        }

        public Builder requestId(Long requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder voteCount(Long voteCount) {
            this.voteCount = voteCount;
            return this;
        }

        public Builder frameworks(List<String> frameworks) {
            this.frameworks = frameworks;
            return this;
        }

        public Builder objectClassCapabilities(List<ObjectClassCapabilityDto> objectClassCapabilities) {
            this.objectClassCapabilities = objectClassCapabilities;
            return this;
        }

        public ApplicationDto build() {
            return new ApplicationDto(
                    id,
                    displayName,
                    description,
                    logoPath,
                    lifecycleState,
                    updated,
                    createdAt,
                    capabilities,
                    requester,
                    origins,
                    categories,
                    tags,
                    integrationMethods,
                    requestId,
                    voteCount,
                    frameworks,
                    objectClassCapabilities
            );
        }
    }
}
