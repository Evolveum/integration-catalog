/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
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
    @JdbcType(value = PostgreSQLEnumJdbcType.class)
    @Column(name = "tag_type", columnDefinition = "applicationTagType", nullable = false)
    private ApplicationTagType tagType;

    @OneToMany(mappedBy = "applicationTag", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ApplicationApplicationTag> applicationApplicationTags;
}
