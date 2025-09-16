package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
    private UUID applicationId;

    @Enumerated(EnumType.STRING)
    @JdbcType(value = PostgreSQLEnumJdbcType.class)
    @Column(columnDefinition = "CapabilitiesType")
    private Request.CapabilitiesType capabilitiesType;

    private String requester;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = false)
    private List<Votes> votes = new ArrayList<>();

    @Transient
    public long getVotesCount() {
        return votes == null ? 0 : votes.size();
    }
}
