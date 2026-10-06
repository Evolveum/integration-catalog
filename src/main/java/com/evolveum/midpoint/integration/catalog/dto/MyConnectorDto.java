/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import com.evolveum.midpoint.integration.catalog.object.SupportTier;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A connector the caller maintains, with its versions, as the My connectors page lists it.
 *
 * @param versions newest first
 */
public record MyConnectorDto(
        Integer id,
        String displayName,
        MaintainerDto maintainer,
        List<String> tags,          // display names, sorted
        List<Version> versions
) {

    /**
     * @param version the "Connector version" given when the connector was added (bundle version)
     * @param usedBy the method revisions linking this version
     */
    public record Version(
            String version,
            String author,
            LocalDate uploaded,
            String lifecycleState,
            Integer bundleVersionId,    // null when the version has no bundle version
            SupportTier supportTier,
            List<Usage> usedBy
    ) {}

    public record Usage(
            UUID applicationId,
            String applicationDisplayName,
            UUID integrationMethodId,
            String revision,
            String displayName
    ) {}
}
