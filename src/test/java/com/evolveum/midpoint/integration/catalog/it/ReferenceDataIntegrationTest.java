/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.it;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The lookup lists postgres.sql seeds are readable anonymously, as the upload and request forms
 * need them before anyone signs in.
 */
class ReferenceDataIntegrationTest extends AbstractCatalogIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/integration-method-types",
            "/api/midpoint-versions",
            "/api/capabilities",
            "/api/application-tags"
    })
    void seededListIsPublicAndNotEmpty(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", not(empty())));
    }
}
