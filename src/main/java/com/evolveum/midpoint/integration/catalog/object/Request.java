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

import java.util.ArrayList;
import java.util.List;

/**
 * Created by TomasS.
 */
@Entity
@Table(name = "request")
@Getter @Setter
public class Request {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @Column(name = "capabilities", columnDefinition = "LONGTEXT")
    private String capabilities;

    private String requester;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = false)
    private List<Vote> votes = new ArrayList<>();

    @Transient
    public long getVotesCount() {
        return votes == null ? 0 : votes.size();
    }
}
