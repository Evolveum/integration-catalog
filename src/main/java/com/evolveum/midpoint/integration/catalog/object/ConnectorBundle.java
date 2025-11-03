package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "connector_bundle")
@Getter @Setter
@Accessors(chain = true)
public class ConnectorBundle {

    public enum FrameworkType {
        CONNID,
        SCIM_REST
    }

    public enum LicenseType {
        MIT,
        APACHE_2,
        BSD,
        EUPL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "bundle_name", nullable = false, unique = true)
    private String bundleName;

    @Column(name = "maintainer")
    private String maintainer;

    @Enumerated(EnumType.STRING)
    @Column(name = "framework", columnDefinition = "varchar(64)", nullable = false)
    private FrameworkType framework;

    @Enumerated(EnumType.STRING)
    @Column(name = "license", columnDefinition = "varchar(64)", nullable = false)
    private LicenseType license;

    @Column(name = "link_on_ticketing_system", columnDefinition = "TEXT")
    private String linkOnTicketingSystem;

    @OneToMany(mappedBy = "connectorBundle")
    private List<Implementation> implementations = new ArrayList<>();
}
