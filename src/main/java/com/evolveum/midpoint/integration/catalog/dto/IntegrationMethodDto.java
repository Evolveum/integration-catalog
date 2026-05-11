/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record IntegrationMethodDto(
        UUID id,
        String description,
        List<String> implementationTags,
        List<String> capabilities,
        List<ObjectClassCapabilityDto> objectClassCapabilities,
        String connectorVersion,
        String systemVersion,
        LocalDate releasedDate,
        String author,
        Integer organizationId,
        String lifecycleState,
        String downloadLink,
        String framework,
        String errorMessage,
        Long downloadCount,
        Integer midpointMinVersionId,
        Integer midpointMaxVersionId,
        String connectorDisplayName,
        List<String> integMethodTypes,
        String revision
) {}