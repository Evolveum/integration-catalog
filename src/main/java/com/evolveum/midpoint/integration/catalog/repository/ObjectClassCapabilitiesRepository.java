/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.ObjectClassCapabilities;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ObjectClassCapabilitiesRepository extends JpaRepository<ObjectClassCapabilities, Long> {
    List<ObjectClassCapabilities> findByRequestId(Long requestId);
}
