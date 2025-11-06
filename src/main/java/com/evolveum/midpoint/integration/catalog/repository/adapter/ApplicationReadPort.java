/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.repository.adapter;

import com.evolveum.midpoint.integration.catalog.object.Application;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;

public interface ApplicationReadPort {
    Page<Application> findAll(Pageable pageable);
    Page<Application> findFeatured(Pageable pageable);
    Page<Application> searchByName(String q, Pageable pageable);
    Application getById(UUID id);
}
