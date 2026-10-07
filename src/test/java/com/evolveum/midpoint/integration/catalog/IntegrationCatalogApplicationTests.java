/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog;

import com.evolveum.midpoint.integration.catalog.controller.Controller;
import com.evolveum.midpoint.integration.catalog.it.AbstractCatalogIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole context starts against the test database, which includes the schema version check.
 */
class IntegrationCatalogApplicationTests extends AbstractCatalogIntegrationTest {

    @Autowired
    private Controller controller;

	@Test
	void contextLoads() {
        assertThat(controller).isNotNull();
	}
}
