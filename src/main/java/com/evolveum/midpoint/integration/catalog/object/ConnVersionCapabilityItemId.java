/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import java.io.Serializable;
import java.util.Objects;

public class ConnVersionCapabilityItemId implements Serializable {

    private Integer connVersionCapabilityId;
    private Integer capabilityId;

    public ConnVersionCapabilityItemId() {}

    public ConnVersionCapabilityItemId(Integer connVersionCapabilityId, Integer capabilityId) {
        this.connVersionCapabilityId = connVersionCapabilityId;
        this.capabilityId = capabilityId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConnVersionCapabilityItemId that)) return false;
        return Objects.equals(connVersionCapabilityId, that.connVersionCapabilityId)
                && Objects.equals(capabilityId, that.capabilityId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(connVersionCapabilityId, capabilityId);
    }
}
