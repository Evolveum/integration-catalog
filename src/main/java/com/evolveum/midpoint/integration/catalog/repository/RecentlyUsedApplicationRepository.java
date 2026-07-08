/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.RecentlyUsedApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RecentlyUsedApplicationRepository extends JpaRepository<RecentlyUsedApplication, Long> {

    List<RecentlyUsedApplication> findAllByOrderByIdDesc();

    void deleteByUserIdAndApplicationId(String userId, UUID applicationId);
}
