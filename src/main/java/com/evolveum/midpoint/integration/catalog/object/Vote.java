/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.object;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

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

