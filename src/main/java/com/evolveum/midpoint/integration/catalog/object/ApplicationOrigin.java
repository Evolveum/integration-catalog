package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Created by Dominik.
 */
@Entity
@Table(name = "application_origin")
@Getter @Setter
public class ApplicationOrigin {

    @Id
    @Column(name = "application_id")
    private UUID applicationId;

    @Id
    @Column(name = "country_id")
    private int countryId;

    @ManyToOne
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @ManyToOne
    @JoinColumn(name = "country_id", nullable = false)
    private CountryOfOrigin countryOfOrigin;
}
