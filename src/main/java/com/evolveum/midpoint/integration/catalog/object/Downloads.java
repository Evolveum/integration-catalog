package com.evolveum.midpoint.integration.catalog.object;

import com.evolveum.midpoint.integration.catalog.utils.Inet;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Created by TomasS.
 */
@Entity
@Table(name = "downloads")
@Getter @Setter
public class Downloads {

    @Column(name = "implementation_version_id")
    private String implementationVersionId;

    @Column(name = "ip_address", columnDefinition = "inet", nullable = false)
    private Inet ipAddress;

    @Column(name = "user_agent", nullable = false)
    private String userAgent;

    @Column(name = "downloaded_at", columnDefinition = "TIMESTAMPTZ", nullable = false)
    private OffsetDateTime downloadedAt;
}
