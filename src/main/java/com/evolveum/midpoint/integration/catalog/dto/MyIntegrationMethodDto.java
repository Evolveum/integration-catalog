/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One revision of an integration method the caller maintains, as the My integration methods page
 * lists it.
 *
 * @param createdAt the original submission date, shared by all revisions
 * @param updated last change; the review start date while REVIEWING
 * @param supportTicketUrl null when the support portal is not configured
 */
public record MyIntegrationMethodDto(
        UUID applicationId,
        String applicationDisplayName,
        boolean applicationHasLogo,
        UUID id,
        String revision,
        String displayName,
        String connectorDisplayName,
        String lifecycleState,
        LocalDate createdAt,
        LocalDate updated,
        String author,
        Integer supportTicketId,
        String supportTicketUrl,
        MaintainerDto maintainer
) {}
