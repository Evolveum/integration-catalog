/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    List<ApiKey> findByOwnerSubOrderByCreatedAtDesc(String ownerSub);

    /** A key and the keys that renewed or were renewed by it: they share one subscription. */
    List<ApiKey> findByGraviteeSubscriptionId(String graviteeSubscriptionId);
}
