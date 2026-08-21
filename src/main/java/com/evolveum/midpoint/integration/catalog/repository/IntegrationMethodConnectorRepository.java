/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodConnector;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IntegrationMethodConnectorRepository extends JpaRepository<IntegrationMethodConnector, Integer> {

    /** How many integration-method revisions link a given connector (i.e. whether it is shared). */
    long countByConnector_Id(Integer connectorId);

    /** Every link pointing at a connector, so they can be moved when that connector is retired. */
    List<IntegrationMethodConnector> findByConnector_Id(Integer connectorId);
}
