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
 * The canonical maintainer referred to by the relevant entry. 
 * The record contains the ID and details of the maintainer – that is, their identifier and category.
 */
@Entity
@Table(name = "maintainers")
@Getter @Setter
@Accessors(chain = true)
public class Maintainer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** A person's username */
    @Column(nullable = false)
    private String username;

    /** An organization id (see {@link MaintainerType#ORG}) */
    @OneToOne
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @Enumerated(EnumType.STRING)
    @JdbcType(value = PostgreSQLEnumJdbcType.class)
    @Column(name = "category", columnDefinition = "MaintainerType", nullable = false)
    private MaintainerType category;
}