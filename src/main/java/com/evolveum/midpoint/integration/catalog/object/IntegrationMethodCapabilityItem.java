/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "integration_method_capability_item")
@IdClass(IntegrationMethodCapabilityItemId.class)
@Getter @Setter
public class IntegrationMethodCapabilityItem {

    @Id
    @Column(name = "integration_method_capability_id")
    private Integer integrationMethodCapabilityId;

    @Id
    @Column(name = "capability_id")
    private Integer capabilityId;

    @ManyToOne
    @JoinColumn(name = "integration_method_capability_id", insertable = false, updatable = false)
    private IntegrationMethodCapability integrationMethodCapability;

    @ManyToOne
    @JoinColumn(name = "capability_id", insertable = false, updatable = false)
    private Capability capability;
}
