/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.utils;

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
