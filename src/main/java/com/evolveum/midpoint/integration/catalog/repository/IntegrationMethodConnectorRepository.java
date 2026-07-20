/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodConnector;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationMethodConnectorRepository extends JpaRepository<IntegrationMethodConnector, Integer> {

    /** How many integration-method revisions link a given connector (i.e. whether it is shared). */
    long countByConnector_Id(Integer connectorId);
}
