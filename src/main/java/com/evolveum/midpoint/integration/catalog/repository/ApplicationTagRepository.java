package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.ApplicationTag;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface ApplicationTagRepository extends JpaRepository<ApplicationTag, UUID>,
        JpaSpecificationExecutor<ApplicationTag> {
}
