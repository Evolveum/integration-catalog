/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.PendingRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingRequestRepository extends JpaRepository<PendingRequest, Integer> {
}
