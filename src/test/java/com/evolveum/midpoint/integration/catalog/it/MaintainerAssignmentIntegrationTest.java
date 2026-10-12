/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.it;

import com.evolveum.midpoint.integration.catalog.security.CatalogRole;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who a publisher may name as maintainer: the publish form offers a non-superuser only themselves,
 * Community and their own organization, and the server holds them to that for requests sent
 * around the form.
 */
class MaintainerAssignmentIntegrationTest extends AbstractCatalogIntegrationTest {

    private static final RequestPostProcessor INDIVIDUAL = user("it-individual", CatalogRole.INDIVIDUAL_CONTRIBUTOR);
    private static final RequestPostProcessor SUPERUSER = user("it-admin", CatalogRole.SUPERUSER);

    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> displayNames = new ArrayList<>();

    @AfterEach
    void deleteConnectors() {
        for (String displayName : displayNames) {
            jdbc.update("""
                    delete from connector_bundle where id in (
                        select c.connector_bundle_id from connector c
                        join integration_method_connector imc on imc.connector_id = c.id
                        join integration_method im on im.id = imc.integ_method_id
                        join application a on a.id = im.application_id
                        where a.display_name = ?)""", displayName);
            // Through the database's cascades: JPA cannot delete an application that has methods.
            jdbc.update("delete from application where display_name = ?", displayName);
        }
    }

    @Test
    void individualMayNotPublishForAnotherUser() throws Exception {
        publish(INDIVIDUAL, "{\"category\": \"USER\", \"username\": \"it-someone-else\"}", status().isForbidden());
    }

    @Test
    void individualMayNotPublishForAnOrganization() throws Exception {
        String organization = jdbc.queryForList("select name from organizations", String.class)
                .stream().findFirst().orElse("it-org");
        publish(INDIVIDUAL, "{\"category\": \"ORG\", \"organizationName\": \"" + organization + "\"}",
                status().is4xxClientError());
    }

    @Test
    void individualMayNotPublishAsEvolveum() throws Exception {
        publish(INDIVIDUAL, "{\"category\": \"EVOLVEUM\"}", status().isForbidden());
    }

    @Test
    void individualMayPublishForThemselvesOrCommunity() throws Exception {
        publish(INDIVIDUAL, "{\"category\": \"USER\", \"username\": \"it-individual\"}", status().isOk());
        publish(INDIVIDUAL, "{\"category\": \"COMMUNITY\"}", status().isOk());
    }

    @Test
    void superuserMayPublishForAnyone() throws Exception {
        publish(SUPERUSER, "{\"category\": \"USER\", \"username\": \"it-someone-else\"}", status().isOk());
    }

    /** Publishes a new application, method and connector, all maintained by {@code maintainerJson}. */
    private void publish(RequestPostProcessor publisher, String maintainerJson, ResultMatcher expected) throws Exception {
        String displayName = uniqueDisplayName("Maintainer");
        displayNames.add(displayName);
        Integer midpointVersion = jdbc.queryForObject("select min(id) from midpoint_version", Integer.class);
        Integer methodType = jdbc.queryForObject("select min(id) from integration_method_type", Integer.class);
        String body = """
                {"application": {"id": null, "displayName": "%s", "description": "IT", "logo": null,
                                 "origins": [], "tags": []},
                 "integrationMethod": {"id": null, "displayName": "IT method", "revision": "1.0",
                                       "description": "IT", "limitations": "", "tutorial": "",
                                       "maintainer": %s, "typeIds": [%d],
                                       "midpointMinVersion": %d, "midpointMaxVersion": null,
                                       "appMinVersion": null, "appMaxVersion": null},
                 "connector": {"displayName": "IT connector", "description": "IT", "maintainer": %s,
                               "framework": "JAVA_BASED", "license": "APACHE_2", "ticketingSystemLink": null,
                               "projectHomepage": "https://example.com/it", "gitCloneUrl": "https://example.com/it.git",
                               "buildFramework": "MAVEN", "pathToProject": null,
                               "className": "com.example.it.Connector%s", "version": "1.0.0", "commitTag": "v1",
                               "bundleDisplayName": null, "existingConnectorId": null,
                               "connectorMinVersion": "1.0.0", "connectorMaxVersion": null},
                 "files": [], "integrationMethodCapabilities": [], "connectorCapabilities": []}
                """.formatted(displayName, maintainerJson, methodType, midpointVersion, maintainerJson,
                UUID.randomUUID().toString().replace("-", ""));
        mockMvc.perform(post("/api/upload/integration")
                        .with(publisher).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(expected);
    }
}
