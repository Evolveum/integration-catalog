package com.evolveum.midpoint.integration.catalog.object;

import com.evolveum.midpoint.integration.catalog.utils.Inet;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Created by TomasS.
 */
@Entity
@Table(name = "downloads")
@Getter @Setter
public class Downloads {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "implementation_version_id", nullable = false)
    private UUID implementationVersion;

    @Column(name = "ip_address", columnDefinition = "inet", nullable = false)
    private Inet ipAddress;

    @Column(name = "user_agent", nullable = false)
    private String userAgent;

    @Column(name = "downloaded_at", columnDefinition = "TIMESTAMPTZ", nullable = false)
    private OffsetDateTime downloadedAt;
}
