/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import java.io.Serializable;
import java.util.Objects;

public class ConnectorVersionId implements Serializable {

    private Integer id;
    private String revision;

    public ConnectorVersionId() {}

    public ConnectorVersionId(Integer id, String revision) {
        this.id = id;
        this.revision = revision;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConnectorVersionId that)) return false;
        return Objects.equals(id, that.id) && Objects.equals(revision, that.revision);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, revision);
    }
}
