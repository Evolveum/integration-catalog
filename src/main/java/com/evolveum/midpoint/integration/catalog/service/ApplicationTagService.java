/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.ApplicationTagDto;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.evolveum.midpoint.integration.catalog.repository.ApplicationApplicationTagRepository;
import com.evolveum.midpoint.integration.catalog.repository.ApplicationTagRepository;
import com.evolveum.midpoint.integration.catalog.repository.CountryOfOriginRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Created by Tomas
 * Service for managing application tags and origins.
 * Provides a unified pattern for: normalize input -> find-or-create entity -> check if exists -> create join entity.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationTagService {

    private final ApplicationTagRepository applicationTagRepository;
    private final CountryOfOriginRepository countryOfOriginRepository;
    private final ApplicationApplicationTagRepository applicationApplicationTagRepository;

    /**
     * process origin countries for an application.
     * pattern: normalize -> find-or-create CountryOfOrigin -> check exists -> create ApplicationOrigin join.
     *
     * @param application the application to add origins to
     * @param originDisplayNames list of country display names (e.g., "United States", "Czech Republic")
     * @param clearExisting if true, clears existing origins before adding new ones
     */
    @Transactional
    public void processOrigins(Application application, List<String> originDisplayNames, boolean clearExisting) {
        if (originDisplayNames == null || originDisplayNames.isEmpty()) {
            return;
        }

        initializeOriginsSet(application, clearExisting);

        for (String displayName : originDisplayNames) {
            if (isBlank(displayName)) {
                continue;
            }

            String normalizedName = normalizeToSnakeCase(displayName);
            CountryOfOrigin country = findOrCreateCountry(normalizedName, displayName.trim());

            if (!hasOrigin(application, country)) {
                ApplicationOrigin origin = createOriginJoin(application, country);
                application.getApplicationOrigins().add(origin);
            }
        }
    }

    /**
     * process application tags for an application.
     * pattern: find-or-create ApplicationTag -> check exists -> create ApplicationApplicationTag join.
     *
     * @param application the application to add tags to
     * @param tagDtos list of tag DTOs with name and tagType
     * @param clearExisting if true, clears existing tags before adding new ones
     */
    @Transactional
    public void processTags(Application application, List<ApplicationTagDto> tagDtos, boolean clearExisting) {
        if (tagDtos == null || tagDtos.isEmpty()) {
            return;
        }

        initializeTagsSet(application, clearExisting);

        for (ApplicationTagDto tagDto : tagDtos) {
            if (tagDto == null || tagDto.name() == null || tagDto.tagType() == null) {
                continue;
            }

            ApplicationTag.ApplicationTagType tagType = parseTagType(tagDto.tagType());
            if (tagType == null) {
                continue;
            }

            // Handle "both" deployment type special case
            if (tagType == ApplicationTag.ApplicationTagType.DEPLOYMENT
                    && "both".equalsIgnoreCase(tagDto.name())) {
                addTagToApplication(application, "cloud-based", ApplicationTag.ApplicationTagType.DEPLOYMENT);
                addTagToApplication(application, "on-premise", ApplicationTag.ApplicationTagType.DEPLOYMENT);
                continue;
            }

            addTagToApplication(application, tagDto.name(), tagType);
        }
    }

    /**
     * add a single tag to an application (in-memory, for transactional batch operations).
     * pattern: find-or-create -> check exists -> create join.
     *
     * @param application the application
     * @param tagName the tag name
     * @param tagType the tag type
     */
    @Transactional
    public void addTagToApplication(Application application, String tagName, ApplicationTag.ApplicationTagType tagType) {
        initializeTagsSet(application, false);

        ApplicationTag tag = findOrCreateTag(tagName, tagType);

        if (!hasTag(application, tag)) {
            ApplicationApplicationTag join = createTagJoin(application, tag);
            application.getApplicationApplicationTags().add(join);
        }
    }

    /**
     * add deployment tags to an application and save directly to DB.
     * used in request form flow where application is already persisted.
     *
     * @param application the persisted application
     * @param deploymentType "cloud-based", "on-premise", or "both"
     */
    @Transactional
    public void saveDeploymentTags(Application application, String deploymentType) {
        if (deploymentType == null || deploymentType.isEmpty()) {
            return;
        }

        if ("both".equalsIgnoreCase(deploymentType)) {
            saveDeploymentTagDirect(application, "cloud-based");
            saveDeploymentTagDirect(application, "on-premise");
        } else {
            saveDeploymentTagDirect(application, deploymentType);
        }
    }

    /**
     * find existing CountryOfOrigin by normalized name, or create a new one.
     */
    private CountryOfOrigin findOrCreateCountry(String normalizedName, String displayName) {
        return countryOfOriginRepository.findByName(normalizedName)
                .orElseGet(() -> {
                    CountryOfOrigin country = new CountryOfOrigin();
                    country.setName(normalizedName);
                    country.setDisplayName(displayName);
                    log.debug("Creating new CountryOfOrigin: {} ({})", displayName, normalizedName);
                    return countryOfOriginRepository.save(country);
                });
    }

    /**
     * find existing ApplicationTag by name and type, or create a new one.
     */
    private ApplicationTag findOrCreateTag(String name, ApplicationTag.ApplicationTagType tagType) {
        return applicationTagRepository.findByNameAndTagType(name, tagType)
                .orElseGet(() -> {
                    ApplicationTag tag = new ApplicationTag();
                    tag.setName(name);
                    tag.setTagType(tagType);
                    tag.setDisplayName(name);
                    log.debug("Creating new ApplicationTag: {} ({})", name, tagType);
                    return applicationTagRepository.save(tag);
                });
    }

    /**
     * check if application already has this country of origin.
     */
    private boolean hasOrigin(Application application, CountryOfOrigin country) {
        return application.getApplicationOrigins().stream()
                .anyMatch(ao -> entityMatches(
                        ao.getCountryOfOrigin().getId(), country.getId(),
                        ao.getCountryOfOrigin().getName(), country.getName()
                ));
    }

    /**
     * check if application already has this tag.
     */
    private boolean hasTag(Application application, ApplicationTag tag) {
        return application.getApplicationApplicationTags().stream()
                .anyMatch(aat -> tagMatches(aat.getApplicationTag(), tag));
    }

    /**
     * generic entity matching: prefer ID comparison, fall back to name.
     */
    private boolean entityMatches(Long existingId, Long newId, String existingName, String newName) {
        if (existingId != null && newId != null) {
            return existingId.equals(newId);
        }
        return existingName.equals(newName);
    }

    /**
     * tag matching: compare by ID or by name + tagType.
     */
    private boolean tagMatches(ApplicationTag existing, ApplicationTag target) {
        if (existing.getId() != null && target.getId() != null) {
            return existing.getId().equals(target.getId());
        }
        return existing.getName().equals(target.getName())
                && existing.getTagType().equals(target.getTagType());
    }

    /**
     * create ApplicationOrigin join entity.
     */
    private ApplicationOrigin createOriginJoin(Application application, CountryOfOrigin country) {
        ApplicationOrigin origin = new ApplicationOrigin();
        origin.setApplication(application);
        origin.setCountryOfOrigin(country);
        return origin;
    }

    /**
     * create ApplicationApplicationTag join entity.
     */
    private ApplicationApplicationTag createTagJoin(Application application, ApplicationTag tag) {
        ApplicationApplicationTag join = new ApplicationApplicationTag();
        join.setApplication(application);
        join.setApplicationTag(tag);
        return join;
    }

    /**
     * save deployment tag directly to DB (for request form flow).
     */
    private void saveDeploymentTagDirect(Application application, String tagName) {
        applicationTagRepository.findByNameAndTagType(tagName, ApplicationTag.ApplicationTagType.DEPLOYMENT)
                .ifPresentOrElse(
                        tag -> {
                            ApplicationApplicationTag join = createTagJoin(application, tag);
                            applicationApplicationTagRepository.save(join);
                        },
                        () -> log.warn("Deployment tag not found: {}", tagName)
                );
    }

    private void initializeOriginsSet(Application application, boolean clear) {
        if (application.getApplicationOrigins() == null) {
            application.setApplicationOrigins(new HashSet<>());
        } else if (clear) {
            application.getApplicationOrigins().clear();
        }
    }

    private void initializeTagsSet(Application application, boolean clear) {
        if (application.getApplicationApplicationTags() == null) {
            application.setApplicationApplicationTags(new HashSet<>());
        } else if (clear) {
            application.getApplicationApplicationTags().clear();
        }
    }

    /**
     * normalize display name to snake_case for storage.
     * United States -> united_states
     */
    private String normalizeToSnakeCase(String displayName) {
        return displayName.toLowerCase().trim().replaceAll("\\s+", "_");
    }

    /**
     * parse tag type string to enum, returns null if invalid.
     */
    private ApplicationTag.ApplicationTagType parseTagType(String tagTypeStr) {
        try {
            return ApplicationTag.ApplicationTagType.valueOf(tagTypeStr);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid tag type: {}", tagTypeStr);
            return null;
        }
    }

    private boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }
}
