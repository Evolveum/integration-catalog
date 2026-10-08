/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.Author;
import com.evolveum.midpoint.integration.catalog.object.Maintainer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * The authors of objects in DB, user that create object. Intended for audit purposes.
 * A person is looked up by its identifier.
 */
public interface AuthorRepository extends JpaRepository<Author, Long> {

    Optional<Author> findByUsername(String username);
}