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

import java.util.Set;

/**
 * Created by Dominik.
 */
@Entity
@Table(name = "application_tag")
@Getter @Setter
public class ApplicationTag {

    public enum ApplicationTagType {
        DEPLOYMENT,
        LOCALITY,
        CATEGORY,
        COMMON
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "tag_type", nullable = false, columnDefinition = "VARCHAR(50)")
    private ApplicationTagType tagType;

    @OneToMany(mappedBy = "applicationTag", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ApplicationApplicationTag> applicationApplicationTags;
}
