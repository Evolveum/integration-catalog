/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.GraviteeApiKey;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface GraviteeApiKeyRepository extends JpaRepository<GraviteeApiKey, UUID> {

    /**
     * The key with its row locked until the transaction ends, so a concurrent caller waits and then
     * reads what this one committed. Must be the transaction's first read of the key: an entity
     * already loaded is not refreshed by the lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from GraviteeApiKey k where k.id = :id")
    Optional<GraviteeApiKey> findByIdForUpdate(@Param("id") UUID id);
}
