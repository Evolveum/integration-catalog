/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.repository.ConnectorBundleRepository;
import com.evolveum.midpoint.integration.catalog.repository.ConnectorBundleVersionRepository;
import com.evolveum.midpoint.integration.catalog.repository.ConnectorRepository;
import com.evolveum.midpoint.integration.catalog.repository.ConnectorVersionRepository;
import com.evolveum.midpoint.integration.catalog.repository.IntegrationMethodRepository;
import com.evolveum.midpoint.integration.catalog.repository.ItemOwnerView;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The users the catalog itself knows about, gathered from the owner columns of the five
 * owned item tables.
 */
@Service
public class CatalogOwnerDirectory {

    private final ConnectorRepository connectorRepository;
    private final ConnectorVersionRepository connectorVersionRepository;
    private final ConnectorBundleRepository connectorBundleRepository;
    private final ConnectorBundleVersionRepository connectorBundleVersionRepository;
    private final IntegrationMethodRepository integrationMethodRepository;

    public CatalogOwnerDirectory(ConnectorRepository connectorRepository,
                                 ConnectorVersionRepository connectorVersionRepository,
                                 ConnectorBundleRepository connectorBundleRepository,
                                 ConnectorBundleVersionRepository connectorBundleVersionRepository,
                                 IntegrationMethodRepository integrationMethodRepository) {
        this.connectorRepository = connectorRepository;
        this.connectorVersionRepository = connectorVersionRepository;
        this.connectorBundleRepository = connectorBundleRepository;
        this.connectorBundleVersionRepository = connectorBundleVersionRepository;
        this.integrationMethodRepository = integrationMethodRepository;
    }

    /**
     * Every user currently designated as a maintainer, de-duplicated and sorted. Authors are
     * deliberately left out: having uploaded an item does not make someone a candidate to
     * maintain another one.
     */
    public List<String> findAllMaintainers() {
        return distinctNames(
                Stream.of(
                                connectorRepository.findDistinctByMaintainerIsNotNull(),
                                connectorVersionRepository.findDistinctByMaintainerIsNotNull(),
                                connectorBundleRepository.findDistinctByMaintainerIsNotNull(),
                                connectorBundleVersionRepository.findDistinctByMaintainerIsNotNull(),
                                integrationMethodRepository.findDistinctByMaintainerIsNotNull())
                        .flatMap(List::stream),
                ItemOwnerView::getMaintainer);
    }

    /** Every user who has authored an item on behalf of the given organization. */
    public List<String> findAuthorsOfOrganization(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            return List.of();
        }
        return distinctNames(
                Stream.of(
                                connectorRepository.findDistinctByAuthorOrgId(organizationId),
                                connectorVersionRepository.findDistinctByAuthorOrgId(organizationId),
                                connectorBundleRepository.findDistinctByAuthorOrgId(organizationId),
                                connectorBundleVersionRepository.findDistinctByAuthorOrgId(organizationId),
                                integrationMethodRepository.findDistinctByAuthorOrgId(organizationId))
                        .flatMap(List::stream),
                ItemOwnerView::getAuthor);
    }

    /** The five tables overlap heavily, so the merged result is de-duplicated and sorted here. */
    private static List<String> distinctNames(Stream<ItemOwnerView> owners,
                                              Function<ItemOwnerView, String> column) {
        SortedSet<String> distinct = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        owners.map(column)
                .filter(name -> name != null && !name.isBlank())
                .forEach(distinct::add);
        return List.copyOf(distinct);
    }
}
