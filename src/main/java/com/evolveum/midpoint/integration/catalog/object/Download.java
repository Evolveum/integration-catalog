/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Created by Tomas.
 */
@Entity
@Table(name = "download")
@Getter @Setter
public class Download {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumns({
        @JoinColumn(name = "connector_bundle_version_id", referencedColumnName = "id", nullable = false),
        @JoinColumn(name = "connector_bundle_version_revision", referencedColumnName = "revision", nullable = false)
    })
    private ConnectorBundleVersion connectorBundleVersion;

    @Column(name = "ip_address", length = 45, nullable = false)
    private String ipAddress;

    @Column(name = "user_agent", nullable = false)
    private String userAgent;

    @Column(name = "downloaded_at", nullable = false)
    private LocalDateTime downloadedAt;
}
