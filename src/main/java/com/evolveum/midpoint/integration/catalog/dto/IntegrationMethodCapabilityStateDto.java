/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import com.evolveum.midpoint.integration.catalog.object.CapabilityState;

public record IntegrationMethodCapabilityStateDto(
        String name,            // capability.name
        CapabilityState state   // integration_method_capability_item.state
) {}
