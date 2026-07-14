/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import com.evolveum.midpoint.integration.catalog.dto.ApplicationTagDto;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Created by Dominik, Tomas.
 */
@Entity
@Table(name = "application")
@Getter @Setter
@Accessors(chain = true)
public class Application {

    public enum ApplicationLifecycleType {
        REQUESTED,
        IN_REVIEW,
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

    @Column(name = "logo_path")
    private String logoPath;

    @Enumerated(EnumType.STRING)
    @JdbcType(value = PostgreSQLEnumJdbcType.class)
    @Column(name = "lifecycle_state", columnDefinition = "applicationLifecycleType")
    private ApplicationLifecycleType lifecycleState;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated")
    private LocalDateTime updated;

    // Ordered by creation time (inherited across revisions) then revision, so a method
    // keeps a stable position in the detail list even after edits/approvals.
    @OneToMany(mappedBy = "application", fetch = FetchType.EAGER)
    @OrderBy("createdAt ASC, revision ASC")
    private List<IntegrationMethod> integrationMethods;

    @OneToMany(mappedBy = "application", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ApplicationApplicationTag> applicationApplicationTags;

    @OneToMany(mappedBy = "application", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ApplicationOrigin> applicationOrigins;

    @Transient
    private List<String> origins;

    @Transient
    private List<ApplicationTagDto> tags;

    public Application addIntegrationMethod(IntegrationMethod integrationMethod) {
        if (integrationMethods == null) {
            integrationMethods = new ArrayList<>();
        }
        integrationMethod.setApplication(this);
        integrationMethods.add(integrationMethod);
        return this;
    }
}
