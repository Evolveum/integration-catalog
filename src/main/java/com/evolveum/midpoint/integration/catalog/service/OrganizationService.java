/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.object.GetOwnershipOneMaintainer;
import com.evolveum.midpoint.integration.catalog.object.Organization;
import com.evolveum.midpoint.integration.catalog.repository.OrganizationRepository;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Resolves organizations: the alias the OIDC claim carries to the catalog's own id, and that id
 * to the display name shown in the catalog.
 */
@Service
public class OrganizationService {

    static final long CACHE_TTL_MILLIS = 60_000;
    private static final String DEFAULT_LABEL_EVOLVEUM = "Evolveum";
    private static final String DEFAULT_LABEL_COMMUNITY = "Community";

    private final OrganizationRepository organizationRepository;
    private final LongSupplier clock;

    private volatile Map<String, String> displayNamesByName = Map.of();
    private volatile boolean loaded;
    private volatile long loadedAt;

    // @Autowired is required here: with the test-seam constructor below the class has two
    // constructors, and Spring would otherwise look for a no-arg one.
    @Autowired
    public OrganizationService(OrganizationRepository organizationRepository) {
        this(organizationRepository, System::currentTimeMillis);
    }

    /** Test seam: lets a test drive the cache expiry without sleeping. */
    OrganizationService(OrganizationRepository organizationRepository, LongSupplier clock) {
        this.organizationRepository = organizationRepository;
        this.clock = clock;
    }

    /**
     * The organization's display name, or {@code null} when the id is null or unknown — an item
     * whose organization has since been removed stays readable, it just shows no organization.
     */
    public String displayName(String organizationName) {
        if (StringUtils.isEmpty(organizationName)) {
            return null;
        }
        return displayNames().get(organizationName);
    }

    public String displayName(Organization organization) {
        if (organization == null) {
            return null;
        }

        if (StringUtils.isNotEmpty(organization.getDisplayName())) {
            return organization.getDisplayName();
        }

        return organization.getName();
    }

//    /**
//     * The identifier back, but only when the organizations table actually knows it;
//     * {@code null} otherwise.
//     */
//    public String registeredId(Integer organizationId) {
//        return displayName(organizationId) != null ? String.valueOf(organizationId) : null;
//    }

    /** All organizations, ordered by display name. */
    public List<String> allNames() {
        return displayNames().values().stream()
                .filter(name -> name != null && !name.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /** The identifier of the organization with this display name, if any. */
    public String displayNameOfName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return displayNames().entrySet().stream()
                .filter(e -> name.trim().equalsIgnoreCase(e.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    //TODO This isn't right class for this method
    /**
     * What to show as an item's maintainer: the maintainer's username, or — when an
     * organization maintains it and therefore no username is recorded — the organization's
     * display name.
     */
    public String maintainerLabel(GetOwnershipOneMaintainer item) {
        if (item == null || item.getMaintainer() == null) {
            return null;
        }
        switch (item.getMaintainer().getCategory()) {
            case null -> {
                //TODO exception(has to exist)
                return null;
            }
            case EVOLVEUM -> {
                return DEFAULT_LABEL_EVOLVEUM;
            }
            case COMMUNITY -> {
                return DEFAULT_LABEL_COMMUNITY;
            }
            case USER -> {
                return item.getMaintainer().getUsername();
            }
            case ORG -> {
                return displayName(item.getMaintainer().getOrganization());
            }
        }
    }

    /** Identifiers of the organizations whose display name contains the given text. */
    public List<String> idsOfNamesContaining(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String needle = text.trim().toLowerCase();
        return displayNames().entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().toLowerCase().contains(needle))
                .map(Map.Entry::getKey)
                .toList();
    }

    private Map<String, String> displayNames() {
        long now = clock.getAsLong();
        if (!loaded || now - loadedAt >= CACHE_TTL_MILLIS) {
            Map<String, String> freshlyLoaded = new LinkedHashMap<>();
            for (Organization organization : organizationRepository.findAll()) {
                String displayName = organization.getName();
                if (StringUtils.isNotEmpty(organization.getDisplayName())) {
                    displayName = organization.getDisplayName();
                }
                freshlyLoaded.put(organization.getName(), displayName);
            }
            displayNamesByName = Map.copyOf(freshlyLoaded);
            loadedAt = now;
            loaded = true;
        }
        return displayNamesByName;
    }

    public Organization getOrganizationById(Integer organizationId) {
        return organizationRepository.findById(organizationId).orElse(null);
    }

    public Organization getOrganizationByName(String name) {
        return organizationRepository.findByName(name).orElse(null);
    }
}
