/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.Vote;
import com.evolveum.midpoint.integration.catalog.object.VoteId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface VoteRepository extends JpaRepository<Vote, VoteId>,
        JpaSpecificationExecutor<Vote> {

    long countByRequestId(Long requestId);

    boolean existsByRequestIdAndVoter(Long requestId, String voter);

    Optional<Vote> findByRequestIdAndVoter(Long requestId, String voter);
}
