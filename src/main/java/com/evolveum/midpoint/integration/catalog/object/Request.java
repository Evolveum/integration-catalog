/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import com.evolveum.midpoint.integration.catalog.object.ImplementationVersion.CapabilitiesType;
import com.evolveum.midpoint.integration.catalog.repository.adapter.CapabilitiesArrayConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;

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

    @Convert(converter = CapabilitiesArrayConverter.class)
    @ColumnTransformer(write = "?::\"CapabilityType\"[]")
    @Column(name = "capabilities")
    private CapabilitiesType[] capabilities;

    private String requester;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = false)
    private List<Vote> votes = new ArrayList<>();

    @Transient
    public long getVotesCount() {
        return votes == null ? 0 : votes.size();
    }
}
