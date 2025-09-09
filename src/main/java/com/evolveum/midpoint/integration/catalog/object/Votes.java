package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Created by TomasS.
 */
@Entity
@Table(name = "votes")
@Getter @Setter
public class Votes {

    @Column(name = "request_id", nullable = false)
    private String requestId;

    @Column(name = "voter", nullable = false)
    private String voter;
}

