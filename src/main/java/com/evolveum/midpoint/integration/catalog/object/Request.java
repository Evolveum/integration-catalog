/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Created by TomasS.
 */
@Entity
@Table(name = "request")
@Getter @Setter
public class Request {

    public enum CapabilitiesType {
        READ,
        CREATE,
        UPDATE,
        DELETE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @Enumerated(EnumType.STRING)
    @JdbcType(value = PostgreSQLEnumJdbcType.class)
    @Column(columnDefinition = "CapabilitiesType")
    private Request.CapabilitiesType capabilitiesType;

    private String requester;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = false)
    private List<Votes> votes = new ArrayList<>();

    @Transient
    public long getVotesCount() {
        return votes == null ? 0 : votes.size();
    }
}
