/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for Vote entity.
 * Created by TomasS.
 */
@Setter
@Getter
public class VoteId implements Serializable {
    private Long requestId;
    private String voter;

    public VoteId() {}

    public VoteId(Long requestId, String voter) {
        this.requestId = requestId;
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
