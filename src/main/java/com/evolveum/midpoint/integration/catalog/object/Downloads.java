/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.object;

import com.evolveum.midpoint.integration.catalog.utils.Inet;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Created by Tomas.
 */
@Entity
@Table(name = "downloads")
@Getter @Setter
public class Downloads {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "implementation_version_id", nullable = false)
    private ImplementationVersion implementationVersion;

    @Column(name = "ip_address", columnDefinition = "inet", nullable = false)
    private Inet ipAddress;

    @Column(name = "user_agent", nullable = false)
    private String userAgent;

    @Column(name = "downloaded_at", columnDefinition = "TIMESTAMPTZ", nullable = false)
    private OffsetDateTime downloadedAt;
}
