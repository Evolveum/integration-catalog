/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.ApiKey;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    List<ApiKey> findByOwnerSubOrderByCreatedAtDesc(String ownerSub);

    /** A key and the keys that renewed or were renewed by it: they share one subscription. */
    List<ApiKey> findByGraviteeSubscriptionId(String graviteeSubscriptionId);

    /**
     * The key with its row locked until the transaction ends, so a concurrent caller waits and then
     * reads what this one committed. Must be the transaction's first read of the key: an entity
     * already loaded is not refreshed by the lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from ApiKey k where k.id = :id")
    Optional<ApiKey> findByIdForUpdate(@Param("id") UUID id);
}
