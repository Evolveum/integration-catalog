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
        Application application,                                                  // application table entity
        UploadIntegrationMethodDto integrationMethod,                             // integration_method table
        UploadConnectorDto connector,                                             // connector + connector_bundle tables
        List<ItemFile> files,                                                     // uploaded connector JAR files
        List<IntegrationMethodCapabilityGroupDto> integrationMethodCapabilities,  // integration_method_capability / integration_method_capability_item
        List<IntegrationMethodCapabilityGroupDto> connectorCapabilities           // conn_version_capability / conn_version_capability_item
) {
}
