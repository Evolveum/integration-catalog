/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.object;

import lombok.Getter;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for Vote entity.
 * Created by TomasS.
 */
@Getter
public class VoteId implements Serializable {
    private Long requestId;
    private String voter;

    public VoteId() {}

    public VoteId(Long requestId, String voter) {
        this.requestId = requestId;
        this.voter = voter;
    }

    public void setRequestId(Long requestId) {
        this.requestId = requestId;
    }

    public void setVoter(String voter) {
        this.voter = voter;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VoteId voteId = (VoteId) o;
        return Objects.equals(requestId, voteId.requestId) && Objects.equals(voter, voteId.voter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(requestId, voter);
    }
}
