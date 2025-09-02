package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.ConnidVersion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ConnidVersionRepository extends JpaRepository<ConnidVersion, String>,
        JpaSpecificationExecutor<ConnidVersion> {
}
