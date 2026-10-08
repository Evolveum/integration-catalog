/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

/**
 * Who maintains a catalog item, and so who may edit it. What the row carries depends on its
 * {@link MaintainerType} - see there.
 */
@Entity
@Table(name = "maintainers")
@Getter @Setter
@Accessors(chain = true)
public class Maintainer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Set on a {@link MaintainerType#USER} row only; null on every other. */
    @Column(nullable = false)
    private String username;

    /** Set on a {@link MaintainerType#ORG} row only; null on every other. */
    @OneToOne
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @Enumerated(EnumType.STRING)
    @JdbcType(value = PostgreSQLEnumJdbcType.class)
    @Column(name = "category", columnDefinition = "MaintainerType", nullable = false)
    private MaintainerType category;
}