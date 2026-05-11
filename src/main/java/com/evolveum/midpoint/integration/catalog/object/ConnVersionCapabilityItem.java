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
@Table(name = "conn_version_capability_item")
@IdClass(ConnVersionCapabilityItemId.class)
@Getter @Setter
public class ConnVersionCapabilityItem {

    @Id
    @Column(name = "conn_version_capability_id")
    private Integer connVersionCapabilityId;

    @Id
    @Column(name = "capability_id")
    private Integer capabilityId;

    @ManyToOne
    @JoinColumn(name = "conn_version_capability_id", insertable = false, updatable = false)
    private ConnVersionCapability connVersionCapability;

    @ManyToOne
    @JoinColumn(name = "capability_id", insertable = false, updatable = false)
    private Capability capability;
}
