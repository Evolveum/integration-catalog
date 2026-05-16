/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import com.evolveum.midpoint.integration.catalog.common.ItemFile;
import com.evolveum.midpoint.integration.catalog.object.Application;

import java.util.List;

/**
 * Created by Dominik.
 */
public record UploadImplementationDto(
        Application application,
        UploadIntegrationMethodDto integrationMethod,
        UploadConnectorDto connector,
        List<ItemFile> files,
        List<IntegrationMethodCapabilityGroupDto> integrationMethodCapabilities,
        List<IntegrationMethodCapabilityGroupDto> connectorCapabilities
) {
}
