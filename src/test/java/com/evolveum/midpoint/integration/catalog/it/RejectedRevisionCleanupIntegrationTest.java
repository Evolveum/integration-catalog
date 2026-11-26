/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.it;

import com.evolveum.midpoint.integration.catalog.security.CatalogRole;
import com.evolveum.midpoint.integration.catalog.service.ConnectorUploadService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the daily cleanup does with each revision left REJECTED past the retention: it goes with
 * what only it brought in, while younger rejections and everything published stay. The job itself
 * is not run, as it would also sweep the shared test database's own old rejections.
 */
class RejectedRevisionCleanupIntegrationTest extends AbstractCatalogIntegrationTest {

    private static final String OWNER_NAME = "it-owner";
    private static final RequestPostProcessor OWNER = user(OWNER_NAME, CatalogRole.INDIVIDUAL_CONTRIBUTOR);

    @Autowired
    private ConnectorUploadService connectorUploadService;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> applicationIds = new ArrayList<>();

    @AfterEach
    void deletePublished() {
        for (UUID applicationId : applicationIds) {
            jdbc.update("""
                    delete from connector_bundle where id in (
                        select c.connector_bundle_id from connector c
                        join integration_method_connector imc on imc.connector_id = c.id
                        join integration_method im on im.id = imc.integ_method_id
                        where im.application_id = ?)""", applicationId);
            jdbc.update("delete from application where id = ?", applicationId);
        }
    }

    @Test
    void anExpiredRejectionGoesWithItsConnectorAndNewApplication() throws Exception {
        Published rejected = publish(null);
        List<Integer> connectorIds = connectorIdsOf(rejected.methodId());
        reject(rejected.methodId(), 91);

        purge(rejected.methodId());

        assertThat(methodCount(rejected.methodId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from connector where id = any(?)", Integer.class,
                (Object) connectorIds.toArray(new Integer[0]))).isZero();
        assertThat(jdbc.queryForObject("select count(*) from application where id = ?", Integer.class,
                rejected.applicationId())).isZero();
    }

    @Test
    void aYoungerRejectionStays() throws Exception {
        Published rejected = publish(null);
        reject(rejected.methodId(), 89);

        purge(rejected.methodId());

        assertThat(methodCount(rejected.methodId())).isOne();
    }

    @Test
    void theApplicationAndItsActiveMethodStay() throws Exception {
        Published active = publish(null);
        jdbc.update("update integration_method set lifecycle_state = 'ACTIVE' where id = ?", active.methodId());
        Published rejected = publish(active.applicationId());
        reject(rejected.methodId(), 91);

        purge(rejected.methodId());

        assertThat(methodCount(rejected.methodId())).isZero();
        assertThat(methodCount(active.methodId())).isOne();
        assertThat(jdbc.queryForObject("select count(*) from application where id = ?", Integer.class,
                active.applicationId())).isOne();
    }

    /** Purges the method with the 90-day cutoff the job uses by default. */
    private void purge(UUID methodId) {
        connectorUploadService.purgeRejectedIntegrationMethod(methodId, "1.0", LocalDateTime.now().minusDays(90));
    }

    /** Marks the method and its connectors rejected the given number of days ago. */
    private void reject(UUID methodId, int daysAgo) {
        jdbc.update("""
                update connector set lifecycle_state = 'REJECTED' where id in (
                    select connector_id from integration_method_connector where integ_method_id = ?)""", methodId);
        jdbc.update("""
                update integration_method set lifecycle_state = 'REJECTED',
                    updated = now() - make_interval(days => ?) where id = ?""", daysAgo, methodId);
    }

    private int methodCount(UUID methodId) {
        return jdbc.queryForObject("select count(*) from integration_method where id = ?", Integer.class, methodId);
    }

    private List<Integer> connectorIdsOf(UUID methodId) {
        return jdbc.queryForList("select connector_id from integration_method_connector where integ_method_id = ?",
                Integer.class, methodId);
    }

    private record Published(UUID applicationId, UUID methodId) {
    }

    /** Publishes a method in review with a new connector; into a new application if null. */
    private Published publish(UUID applicationId) throws Exception {
        Integer midpointVersion = jdbc.queryForObject("select min(id) from midpoint_version", Integer.class);
        Integer methodType = jdbc.queryForObject("select min(id) from integration_method_type", Integer.class);
        String maintainer = "{\"category\": \"USER\", \"username\": \"" + OWNER_NAME + "\"}";
        String body = """
                {"application": {"id": %s, "displayName": "%s", "description": "IT", "logo": null,
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
                               "className": "com.example.it.Cleanup%s", "version": "1.0.0", "commitTag": "v1",
                               "bundleDisplayName": null, "existingConnectorId": null,
                               "connectorMinVersion": "1.0.0", "connectorMaxVersion": null},
                 "files": [], "integrationMethodCapabilities": [], "connectorCapabilities": []}
                """.formatted(applicationId == null ? "null" : "\"" + applicationId + "\"",
                uniqueDisplayName("Cleanup"), maintainer, methodType, midpointVersion, maintainer,
                UUID.randomUUID().toString().replace("-", ""));
        String response = mockMvc.perform(post("/api/upload/integration")
                        .with(OWNER).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String[] ids = response.split("\\|");
        UUID published = UUID.fromString(ids[0]);
        if (!applicationIds.contains(published)) {
            applicationIds.add(published);
        }
        return new Published(published, UUID.fromString(ids[1]));
    }
}
