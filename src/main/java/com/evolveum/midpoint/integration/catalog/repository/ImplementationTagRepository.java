package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.ImplementationTag;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ImplementationTagRepository extends JpaRepository<ImplementationTag, Integer>,
        JpaSpecificationExecutor<ImplementationTag> {
}
