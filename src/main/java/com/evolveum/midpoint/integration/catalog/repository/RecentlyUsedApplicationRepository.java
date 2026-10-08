/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.RecentlyUsedApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RecentlyUsedApplicationRepository extends JpaRepository<RecentlyUsedApplication, Long> {

    List<RecentlyUsedApplication> findAllByOrderByIdDesc();

    void deleteByApplicationId(UUID applicationId);

    /** Deletes every row except the {@code keep} newest ones. */
    @Modifying
    @Query(value = "DELETE FROM recently_used_applications WHERE id NOT IN "
            + "(SELECT id FROM recently_used_applications ORDER BY id DESC LIMIT :keep)", nativeQuery = true)
    int deleteAllButNewest(@Param("keep") int keep);
}
