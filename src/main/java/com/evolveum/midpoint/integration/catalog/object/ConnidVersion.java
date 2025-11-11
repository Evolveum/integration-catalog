/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Created by Dominik.
 */
@Entity
@Table(name = "connid_version")
@Getter @Setter
public class ConnidVersion {

    @Id
    @Column(name = "version")
    private String version;

    @Column(name = "midpoint_version", nullable = false, columnDefinition = "text[]")
    private String[] midpointVersion;
}
