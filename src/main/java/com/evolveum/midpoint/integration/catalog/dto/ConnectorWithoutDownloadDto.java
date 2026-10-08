/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.util.UUID;

/**
 * DTO representing a connector linked to an integration method that lacks download info
 * (no artifactUrl/browseLink set, meaning no build was triggered via Jenkins).
 */
public record ConnectorWithoutDownloadDto(
        Integer connectorId,
        String connectorName,
        String className,
        String bundleName,
        String version,
        String connectorVersionId,
        String connectorVersionRevision,
        UUID integrationMethodId,
        String integrationMethodRevision
) {}