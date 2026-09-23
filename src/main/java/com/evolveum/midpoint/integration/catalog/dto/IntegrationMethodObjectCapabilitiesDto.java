/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import com.evolveum.midpoint.integration.catalog.object.CapabilityState;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;

import java.util.List;

/**
 * The capabilities of one object of an integration method, each with its state. Connectors keep
 * {@link IntegrationMethodCapabilityGroupDto}, which lists supported capabilities only.
 */
public record IntegrationMethodObjectCapabilitiesDto(
        String objectClass,                                       // integration_method_capability.object_class
        List<IntegrationMethodCapabilityStateDto> capabilities    // integration_method_capability_item
) {

    /** An object is only worth stating when it supports something; one without an object class is skipped on save. */
    @JsonIgnore
    @AssertTrue(message = "Every object needs at least one capability marked YES.")
    public boolean isAnyCapabilitySupported() {
        if (objectClass == null || objectClass.isBlank()) {
            return true;
        }
        return capabilities != null && capabilities.stream().anyMatch(c -> c.state() == CapabilityState.YES);
    }
}
