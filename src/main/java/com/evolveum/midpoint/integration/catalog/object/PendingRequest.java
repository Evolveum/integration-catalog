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

/**
 * Entity representing pending integration requests from users.
 */
@Entity
@Table(name = "pending_requests")
@Getter @Setter
@Accessors(chain = true)
public class PendingRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "integration_application_name", columnDefinition = "TEXT")
    private String integrationApplicationName;

    @Column(name = "base_url", columnDefinition = "TEXT")
    private String baseUrl;

    @Column(name = "capabilities", columnDefinition = "LONGTEXT")
    private String capabilities;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "system_version", columnDefinition = "TEXT")
    private String systemVersion;

    @Column(name = "email", columnDefinition = "TEXT")
    private String email;

    @Column(name = "collab", columnDefinition = "TEXT")
    private String collab;

    @Column(name = "requester", columnDefinition = "TEXT")
    private String requester;
}
