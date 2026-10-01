/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the My profile page lists: the integration methods and connectors the caller maintains,
 * personally or through the organization they contribute for.
 *
 * @param integrationMethods every revision, newest first within one method
 * @param connectors one entry per connector
 */
public record MyItemsDto(
        List<IntegrationMethodItem> integrationMethods,
        List<ConnectorItem> connectors
) {

    /**
     * One integration method revision.
     *
     * @param createdAt the original submission date, shared by all revisions
     * @param updated last change; the review start date while REVIEWING
     */
    public record IntegrationMethodItem(
            UUID applicationId,
            String applicationDisplayName,
            boolean applicationHasLogo,
            UUID id,
            String revision,
            String displayName,
            String lifecycleState,
            LocalDate createdAt,
            LocalDate updated
    ) {}

    /**
     * @param lifecycleState ACTIVE once any of its versions is published, else the newest version's state
     * @param usedBy the methods linking it, one entry per method
     */
    public record ConnectorItem(
            Integer id,
            String displayName,
            String version,
            String lifecycleState,
            List<ConnectorUsage> usedBy
    ) {}

    /** A method linking a connector; the revision is its published one when it has one. */
    public record ConnectorUsage(
            UUID applicationId,
            UUID integrationMethodId,
            String revision,
            String displayName
    ) {}
}
