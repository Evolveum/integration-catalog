/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import com.evolveum.midpoint.integration.catalog.utils.InetAddress;

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
public class Download {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "implementation_version_id", nullable = false)
    private ImplementationVersion implementationVersion;

    @Column(name = "ip_address", columnDefinition = "Inet", nullable = false)
    private InetAddress ipAddress;
//    @Column(name = "ip_address", columnDefinition = "varchar(45)", nullable = false)
//    private String ipAddress;

    @Column(name = "user_agent", nullable = false)
    private String userAgent;

    @Column(name = "downloaded_at", nullable = false)
    private OffsetDateTime downloadedAt;
}
