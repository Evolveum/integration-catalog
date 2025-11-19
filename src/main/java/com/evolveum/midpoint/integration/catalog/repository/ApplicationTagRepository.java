/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.ApplicationTag;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationTagRepository extends JpaRepository<ApplicationTag, UUID>,
        JpaSpecificationExecutor<ApplicationTag> {

    List<ApplicationTag> findByTagType(ApplicationTag.ApplicationTagType tagType);

    Optional<ApplicationTag> findByNameAndTagType(String name, ApplicationTag.ApplicationTagType tagType);
}
