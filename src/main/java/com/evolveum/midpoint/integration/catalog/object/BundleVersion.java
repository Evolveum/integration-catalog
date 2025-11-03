package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "bundle_version")
@Getter @Setter
@Accessors(chain = true)
public class BundleVersion {

    public enum BuildFrameworkType {
        MAVEN,
        GRADLE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "connector_version")
    private String connectorVersion;

    @Column(name = "browse_link")
    private String browseLink;

    @Column(name = "checkout_link")
    private String checkoutLink;

    @Column(name = "download_link")
    private String downloadLink;

    @Column(name = "connid_version", columnDefinition = "varchar(64)")
    private String connidVersion;

    @Column(name = "released_date")
    private LocalDate releasedDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "build_framework", columnDefinition = "varchar(64)", nullable = false)
    private BuildFrameworkType buildFramework;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "path_to_project")
    private String pathToProject;

    @OneToMany(mappedBy = "bundleVersion")
    private List<ImplementationVersion> implementationVersions = new ArrayList<>();
}
