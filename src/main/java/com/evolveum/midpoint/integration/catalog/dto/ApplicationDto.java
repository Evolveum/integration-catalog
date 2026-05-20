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
        UUID id,                                                           // application.id
        String displayName,                                                // application.display_name
        String description,                                                // application.description
        String logoPath,                                                   // application.logo_path
        String lifecycleState,                                             // application.lifecycle_state
        LocalDateTime updated,                                             // application.updated
        LocalDateTime createdAt,                                           // application.created_at
        List<String> capabilities,                                         // capability.name via integration_method_capability
        String requester,                                                  // request.requester
        List<CountryOfOriginDto> origins,                                  // country_of_origin via application_origin
        List<ApplicationTagDto> categories,                                // application_tag (CATEGORY) via application_application_tag
        List<ApplicationTagDto> tags,                                      // application_tag via application_application_tag
        List<IntegrationMethodDto> integrationMethods,                     // integration_method
        Long requestId,                                                    // request.id
        Long voteCount,                                                    // computed: count of vote rows
        List<String> frameworks,                                           // connector_bundle.framework
        List<ObjectClassCapabilityDto> objectClassCapabilities             // object_class_capabilities
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
