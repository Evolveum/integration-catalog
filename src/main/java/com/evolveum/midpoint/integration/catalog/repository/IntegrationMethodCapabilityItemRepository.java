/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodCapabilityItem;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodCapabilityItemId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationMethodCapabilityItemRepository
        extends JpaRepository<IntegrationMethodCapabilityItem, IntegrationMethodCapabilityItemId> {
}
