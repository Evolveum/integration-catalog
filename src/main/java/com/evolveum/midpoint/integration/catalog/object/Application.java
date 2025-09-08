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
import lombok.experimental.Accessors;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "application")
@Getter @Setter
@Accessors(chain = true)
public class Application {

    public enum ApplicationLifecycleType {
        REQUESTED,
        IN_PUBLISH_PROCESS,
        ACTIVE,
        WITH_ERROR
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String name;

    @Column(name = "display_name")
    private String displayName;

    private String description;

//    @Lob
//    private byte[] logo;

    @Enumerated(EnumType.STRING)
    @JdbcType(value = PostgreSQLEnumJdbcType.class)
    @Column(columnDefinition = "ApplicationLifecycleType")
    private ApplicationLifecycleType lifecycleState;

    @CreationTimestamp
    @Column(name = "created_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "last_modified", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime lastModified;

    @OneToMany(mappedBy = "application")
    private List<Implementation> implementations;

    @OneToMany(mappedBy = "application", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ApplicationApplicationTag> applicationApplicationTags;

    public Application addImplementation(Implementation implementation) {
        if (implementations == null) {
            implementations = new ArrayList<>();
        }
        implementation.setApplication(this);
        implementations.add(implementation);
        return this;
    }
}
