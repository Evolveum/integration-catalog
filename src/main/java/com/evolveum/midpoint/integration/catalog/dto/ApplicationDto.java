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

public record ApplicationDto(
        UUID id,
        String displayName,
        String description,
        byte[] logo,
        String lifecycleState,
        OffsetDateTime lastModified,
        OffsetDateTime createdAt,
        List<String> capabilities,
        String requester,
        List<CountryOfOriginDto> origins,
        List<ApplicationTagDto> categories,
        List<ApplicationTagDto> tags,
        List<ImplementationVersionDto> implementationVersions,
        Long requestId,
        Long voteCount
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID id;
        private String displayName;
        private String description;
        private byte[] logo;
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

        public Builder logo(byte[] logo) {
            this.logo = logo;
            return this;
        }

        public Builder lifecycleState(String lifecycleState) {
            this.lifecycleState = lifecycleState;
            return this;
        }

        public Builder lastModified(OffsetDateTime lastModified) {
            this.lastModified = lastModified;
            return this;
        }

        public Builder createdAt(OffsetDateTime createdAt) {
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

        public Builder implementationVersions(List<ImplementationVersionDto> implementationVersions) {
            this.implementationVersions = implementationVersions;
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

        public ApplicationDto build() {
            return new ApplicationDto(
                    id,
                    displayName,
                    description,
                    logo,
                    lifecycleState,
                    lastModified,
                    createdAt,
                    capabilities,
                    requester,
                    origins,
                    categories,
                    tags,
                    implementationVersions,
                    requestId,
                    voteCount
            );
        }
    }
}
