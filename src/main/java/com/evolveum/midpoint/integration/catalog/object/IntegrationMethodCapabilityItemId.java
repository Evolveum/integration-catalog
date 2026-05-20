/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import java.io.Serializable;
import java.util.Objects;

public class IntegrationMethodCapabilityItemId implements Serializable {

    private Integer integrationMethodCapabilityId;
    private Integer capabilityId;

    public IntegrationMethodCapabilityItemId() {}

    public IntegrationMethodCapabilityItemId(Integer integrationMethodCapabilityId, Integer capabilityId) {
        this.integrationMethodCapabilityId = integrationMethodCapabilityId;
        this.capabilityId = capabilityId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IntegrationMethodCapabilityItemId that)) return false;
        return Objects.equals(integrationMethodCapabilityId, that.integrationMethodCapabilityId)
                && Objects.equals(capabilityId, that.capabilityId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(integrationMethodCapabilityId, capabilityId);
    }
}
