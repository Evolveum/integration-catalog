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
 * Resolves organizations: the alias the OIDC claim carries to the catalog's own id, and that id
 * to the display name shown in the catalog.
 */
@Service
public class OrganizationService {

    static final long CACHE_TTL_MILLIS = 60_000;

    private final OrganizationRepository organizationRepository;
    private final LongSupplier clock;

    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of());
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
    public String displayName(Integer organizationId) {
        if (organizationId == null) {
            return null;
        }
        return current().namesById().get(organizationId);
    }

    /**
     * The id of the organization the claim's alias names, or {@code null} when the alias is blank
     * or belongs to no organization the catalog has been told about.
     */
    public Integer idOfAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return null;
        }
        return current().idsByAlias().get(alias);
    }

    /** All organizations, ordered by display name. */
    public List<String> allNames() {
        return current().namesById().values().stream()
                .filter(name -> name != null && !name.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /** The id of the organization with this display name, if any. */
    public Integer idOfName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return current().namesById().entrySet().stream()
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

    /** Ids of the organizations whose display name contains the given text. */
    public List<Integer> idsOfNamesContaining(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String needle = text.trim().toLowerCase();
        return current().namesById().entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().toLowerCase().contains(needle))
                .map(Map.Entry::getKey)
                .toList();
    }

    private Snapshot current() {
        long now = clock.getAsLong();
        if (!loaded || now - loadedAt >= CACHE_TTL_MILLIS) {
            Map<Integer, String> names = new LinkedHashMap<>();
            Map<String, Integer> ids = new LinkedHashMap<>();
            for (Organization organization : organizationRepository.findAll()) {
                names.put(organization.getId(), organization.getName());
                if (organization.getAlias() != null) {
                    ids.put(organization.getAlias(), organization.getId());
                }
            }
            snapshot = new Snapshot(Map.copyOf(names), Map.copyOf(ids));
            loadedAt = now;
            loaded = true;
        }
        return snapshot;
    }

    /** Both lookups loaded together, so a reader never sees one refreshed without the other. */
    private record Snapshot(Map<Integer, String> namesById, Map<String, Integer> idsByAlias) {
    }
}
