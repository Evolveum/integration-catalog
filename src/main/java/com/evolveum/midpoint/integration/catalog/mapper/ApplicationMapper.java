/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.mapper;

import com.evolveum.midpoint.integration.catalog.dto.ApplicationDto;
import com.evolveum.midpoint.integration.catalog.dto.ApplicationTagDto;
import com.evolveum.midpoint.integration.catalog.dto.CountryOfOriginDto;
import com.evolveum.midpoint.integration.catalog.dto.ImplementationVersionDto;
import com.evolveum.midpoint.integration.catalog.object.Application;
import com.evolveum.midpoint.integration.catalog.object.ApplicationApplicationTag;
import com.evolveum.midpoint.integration.catalog.object.ApplicationTag;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Stream;

/**
 * Mapper class for converting Application entities to DTOs
 */
@Component
public class ApplicationMapper {

    /**
     * Filters application tags by type and maps to DTOs
     * @param app Application entity
     * @param tagType Tag type to filter by
     * @return List of ApplicationTagDto or null if no tags
     */
    public List<ApplicationTagDto> filterTagsByType(Application app, ApplicationTag.ApplicationTagType tagType) {
        if (app.getApplicationApplicationTags() == null) {
            return null;
        }
        return app.getApplicationApplicationTags().stream()
                .filter(appTag -> appTag.getApplicationTag().getTagType() == tagType)
                .map(this::mapToApplicationTagDto)
                .toList();
    }

    /**
     * Maps all application tags to DTOs
     * @param app Application entity
     * @return List of ApplicationTagDto or null if no tags
     */
    public List<ApplicationTagDto> mapAllTags(Application app) {
        if (app.getApplicationApplicationTags() == null) {
            return null;
        }
        return app.getApplicationApplicationTags().stream()
                .map(this::mapToApplicationTagDto)
                .toList();
    }

    /**
     * Converts ApplicationApplicationTag to ApplicationTagDto
     * @param appTag ApplicationApplicationTag entity
     * @return ApplicationTagDto
     */
    public ApplicationTagDto mapToApplicationTagDto(ApplicationApplicationTag appTag) {
        return new ApplicationTagDto(
                appTag.getApplicationTag().getId(),
                appTag.getApplicationTag().getName(),
                appTag.getApplicationTag().getDisplayName(),
                appTag.getApplicationTag().getTagType() != null ? appTag.getApplicationTag().getTagType().name() : null
        );
    }

    /**
     * Maps implementation versions from Application to DTOs
     * @param app Application entity
     * @return List of ImplementationVersionDto or null if no implementations
     */
    public List<ImplementationVersionDto> mapImplementationVersions(Application app) {
        if (app.getImplementations() == null) {
            return null;
        }
        return app.getImplementations().stream()
                .flatMap(impl -> impl.getImplementationVersions() != null ?
                        impl.getImplementationVersions().stream().map(version -> {
                            List<String> implementationTags = null;
                            if (impl.getImplementationImplementationTags() != null) {
                                implementationTags = impl.getImplementationImplementationTags().stream()
                                        .map(tag -> tag.getImplementationTag().getDisplayName())
                                        .toList();
                            }
                            List<String> capabilities = parseCapabilitiesJson(version.getCapabilitiesJson());
                            String lifecycleState = version.getLifecycleState() != null ? version.getLifecycleState().name() : null;
                            return new ImplementationVersionDto(
                                    version.getDescription(),
                                    implementationTags,
                                    capabilities,
                                    version.getConnectorVersion(),
                                    version.getSystemVersion(),
                                    version.getReleasedDate(),
                                    version.getAuthor(),
                                    lifecycleState,
                                    version.getDownloadLink()
                            );
                        }) : Stream.empty())
                .toList();
    }

    /**
     * Parses JSON capabilities string to list of strings
     * @param capabilitiesJson JSON string containing capabilities array
     * @return List of capability strings or null if empty/invalid
     */
    public List<String> parseCapabilitiesJson(String capabilitiesJson) {
        if (capabilitiesJson == null || capabilitiesJson.isEmpty()) {
            return null;
        }
        try {
            // Remove brackets and quotes, split by comma
            String cleaned = capabilitiesJson.replace("[", "").replace("]", "").replace("\"", "");
            if (cleaned.isEmpty()) {
                return null;
            }
            return List.of(cleaned.split(",\\s*"));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Maps Application entity to ApplicationDto with all related data
     * @param app Application entity
     * @param capabilities Request capabilities (null for list view)
     * @param requester Request requester (null for list view)
     * @param requestId Request ID (null if not requested)
     * @param voteCount Vote count (null if not requested)
     * @return ApplicationDto
     */
    public ApplicationDto mapToApplicationDto(Application app,
                                               List<String> capabilities,
                                               String requester,
                                               Long requestId,
                                               Long voteCount) {
        // Map origins
        List<CountryOfOriginDto> origins = null;
        if (app.getApplicationOrigins() != null) {
            origins = app.getApplicationOrigins().stream()
                    .map(appOrigin -> new CountryOfOriginDto(
                            appOrigin.getCountryOfOrigin().getId(),
                            appOrigin.getCountryOfOrigin().getName(),
                            appOrigin.getCountryOfOrigin().getDisplayName()
                    ))
                    .toList();
        }

        // Map categories, tags, and implementation versions
        List<ApplicationTagDto> categories = filterTagsByType(app, ApplicationTag.ApplicationTagType.CATEGORY);
        List<ApplicationTagDto> tags = mapAllTags(app);
        List<ImplementationVersionDto> implementationVersions = mapImplementationVersions(app);

        // Convert lifecycle state
        String lifecycleState = app.getLifecycleState() != null ? app.getLifecycleState().name() : null;

        // Build and return DTO
        return ApplicationDto.builder()
                .id(app.getId())
                .displayName(app.getDisplayName())
                .description(app.getDescription())
                .logo(app.getLogo())
                .lifecycleState(lifecycleState)
                .lastModified(app.getLastModified())
                .createdAt(app.getCreatedAt())
                .capabilities(capabilities)
                .requester(requester)
                .origins(origins)
                .categories(categories)
                .tags(tags)
                .implementationVersions(implementationVersions)
                .requestId(requestId)
                .voteCount(voteCount)
                .build();
    }
}
