/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.object.Organization;
import com.evolveum.midpoint.integration.catalog.object.OwnedItem;
import com.evolveum.midpoint.integration.catalog.repository.OrganizationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Resolves an organization identifier — the value the OIDC organization claim carries — to
 * the display name shown in the catalog.
 * <p>
 * Item lists ask for the same handful of organizations over and over, so the whole (small)
 * table is held for {@value #CACHE_TTL_MILLIS} ms instead of issuing one query per row. A
 * rename therefore becomes visible within that window, without a restart.
 */
@Service
public class OrganizationService {

    static final long CACHE_TTL_MILLIS = 60_000;

    private final OrganizationRepository organizationRepository;
    private final LongSupplier clock;

    private volatile Map<String, String> namesById = Map.of();
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
     * The organization's display name, or {@code null} when the identifier is blank or
     * unknown — an item whose organization has not been seeded stays readable, it just
     * shows no organization.
     */
    public String displayName(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            return null;
        }
        return names().get(organizationId);
    }

    /** All organizations, ordered by display name; used for the maintainer options. */
    public List<String> allNames() {
        return names().values().stream()
                .filter(name -> name != null && !name.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /** The identifier of the organization with this display name, if any. */
    public String idOfName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return names().entrySet().stream()
                .filter(e -> name.trim().equalsIgnoreCase(e.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    /**
     * What to show as an item's maintainer: the maintainer's username, or — when an
     * organization maintains it and therefore no username is recorded — the organization's
     * display name.
     */
    public String maintainerLabel(OwnedItem item) {
        if (item == null) {
            return null;
        }
        return item.getMaintainer() != null
                ? item.getMaintainer()
                : displayName(item.getMaintainerOrgId());
    }

    /** Identifiers of the organizations whose display name contains the given text. */
    public List<String> idsOfNamesContaining(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String needle = text.trim().toLowerCase();
        return names().entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().toLowerCase().contains(needle))
                .map(Map.Entry::getKey)
                .toList();
    }

    private Map<String, String> names() {
        long now = clock.getAsLong();
        // The "never loaded yet" case is a flag rather than a sentinel timestamp: subtracting
        // one from the current time overflows, and the cache would then never fill at all.
        if (!loaded || now - loadedAt >= CACHE_TTL_MILLIS) {
            Map<String, String> freshlyLoaded = new LinkedHashMap<>();
            for (Organization organization : organizationRepository.findAll()) {
                freshlyLoaded.put(organization.getId(), organization.getName());
            }
            namesById = Map.copyOf(freshlyLoaded);
            loadedAt = now;
            loaded = true;
        }
        return namesById;
    }
}
