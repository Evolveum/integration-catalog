package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Created by TomasS.
 */
@Entity
@Table(name = "request")
@Getter @Setter
public class Request {

    public enum CapabilitiesType {
        READ,
        CREATE,
        MODIFY,
        DELETE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    private String requester;
}
