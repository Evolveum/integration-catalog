/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Created by TomasS.
 */
@Entity
@Table(name = "votes")
@IdClass(VoteId.class)
@Getter @Setter
public class Vote {

    @Id
    @Column(name = "request_id")
    private Long requestId;

    @Id
    @Column(name = "voter", nullable = false)
    private String voter;

    @JsonIgnore
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id", nullable = false, insertable = false, updatable = false)
    private Request request;
}

