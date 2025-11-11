/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.repository.adapter;

import com.evolveum.midpoint.integration.catalog.object.Application;
import com.evolveum.midpoint.integration.catalog.repository.ApplicationRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JpaApplicationReadAdapter implements ApplicationReadPort {

    private final ApplicationRepository repo; // Spring Data interface

    @Override
    public Page<Application> findAll(Pageable pageable) {
        return repo.findAll(pageable);
    }

    @Override
    public Page<Application> findFeatured(Pageable pageable) {
        return repo.findByNameContainingIgnoreCase("", pageable); // TODO: Implement featured when field is added to Application
    }

    @Override
    public Page<Application> searchByName(String q, Pageable pageable) {
        return repo.findByNameContainingIgnoreCase(q, pageable);
    }

    @Override
    public Application getById(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Application " + id + " not found"));
    }
}
