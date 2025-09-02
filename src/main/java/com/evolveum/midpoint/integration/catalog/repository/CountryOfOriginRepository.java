package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.CountryOfOrigin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface CountryOfOriginRepository extends JpaRepository<CountryOfOrigin, UUID>,
        JpaSpecificationExecutor<CountryOfOrigin> {
}
