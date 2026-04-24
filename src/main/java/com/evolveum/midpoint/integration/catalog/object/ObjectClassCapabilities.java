/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import com.evolveum.midpoint.integration.catalog.object.ImplementationVersion.CapabilitiesType;
import com.evolveum.midpoint.integration.catalog.repository.adapter.CapabilitiesArrayConverter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;

@Entity
@Table(name = "object_class_capabilities")
@Getter @Setter
public class ObjectClassCapabilities {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id", nullable = false)
    private Request request;

    @Column(name = "object_name", nullable = false, length = 30)
    private String objectName;

    @Convert(converter = CapabilitiesArrayConverter.class)
    @ColumnTransformer(write = "?::\"CapabilityType\"[]")
    @Column(name = "capabilities")
    private CapabilitiesType[] capabilities;
}
