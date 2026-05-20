/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.util.List;

public record IntegrationMethodCapabilityGroupDto(
        String objectClass,          // object_class_capabilities.object_name
        List<String> capabilityNames // capability.name via integration_method_capability_item / conn_version_capability_item
) {}
