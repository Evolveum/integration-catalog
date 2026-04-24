/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by TomasS.
 */
@Entity
@Table(name = "request", uniqueConstraints = {
    @UniqueConstraint(name = "unique_request_per_application", columnNames = "application_id")
})
@Getter @Setter
public class Request {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @Column(name = "requester")
    private String requester;

    @Column(name = "mail", length = 50)
    private String mail;

    @Column(name = "collab", nullable = false)
    private boolean collab = false;

    @Column(name = "base_url")
    private String baseUrl;

    @Column(name = "system_version", length = 20)
    private String systemVersion;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ObjectClassCapabilities> objectClassCapabilities = new ArrayList<>();

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = false)
    private List<Vote> votes = new ArrayList<>();

    @Transient
    public long getVotesCount() {
        return votes == null ? 0 : votes.size();
    }
}
