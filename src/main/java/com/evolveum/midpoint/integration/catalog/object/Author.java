/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * The canonical authors referred to by the relevant entry.
 * The record contains the ID and details of the author – that is, their identifier and mail.
 */
@Entity
@Table(name = "authors")
@Getter @Setter
@Accessors(chain = true)
public class Author {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** A person's login. */
    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private String email;
}