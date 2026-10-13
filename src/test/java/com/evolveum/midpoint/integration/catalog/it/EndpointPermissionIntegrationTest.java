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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Requests a contributor can send straight to the API, past what the UI offers, that must not
 * touch another contributor's application, connector or build.
 */
class EndpointPermissionIntegrationTest extends AbstractCatalogIntegrationTest {

    private static final RequestPostProcessor OWNER = user("it-owner", CatalogRole.INDIVIDUAL_CONTRIBUTOR);
    private static final RequestPostProcessor OTHER = user("it-other", CatalogRole.INDIVIDUAL_CONTRIBUTOR);
    private static final RequestPostProcessor SUPERUSER = user("it-admin", CatalogRole.SUPERUSER);

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
            // Through the database's cascades: JPA cannot delete an application that has methods.
            jdbc.update("delete from application where id = ?", applicationId);
        }
    }

    // ── 1. Publishing a method into somebody else's application ────────────────────────────────

    @Test
    void publishingIntoAnotherApplicationCannotFeatureIt() throws Exception {
        Published owners = publish(OWNER, "null", "[]", "[]");
        List<String> tagsBefore = tagsOf(owners.applicationId());

        // The UI sends tags only for a new application; this one names an existing one.
        publish(OTHER, "\"" + owners.applicationId() + "\"",
                "[{\"name\": \"featured\", \"tagType\": \"COMMON\"}]", "[]");

        assertThat(tagsOf(owners.applicationId())).isEqualTo(tagsBefore);
    }

    @Test
    void anyContributorMayAddOriginsWhilePublishingIntoAnotherApplication() throws Exception {
        Published owners = publish(OWNER, "null", "[]", "[]");
        Object[] country = jdbc.queryForObject("select id, display_name from country_of_origin order by id limit 1",
                (rs, i) -> new Object[] {rs.getInt(1), rs.getString(2)});

        publish(OTHER, "\"" + owners.applicationId() + "\"", "[]", "[\"" + country[1] + "\"]");

        assertThat(originsOf(owners.applicationId())).contains((Integer) country[0]);
    }

    @Test
    void aContributorsNewApplicationKeepsTheWizardTagsButIsNeverFeatured() throws Exception {
        Published mine = publish(OWNER, "null",
                "[{\"name\": \"on-premise\", \"tagType\": \"DEPLOYMENT\"}, {\"name\": \"featured\", \"tagType\": \"COMMON\"}]",
                "[]");

        assertThat(tagsOf(mine.applicationId())).contains("DEPLOYMENT:on-premise").doesNotContain("COMMON:featured");
    }

    @Test
    void superuserMayTagAnExistingApplicationWhilePublishing() throws Exception {
        Published owners = publish(OWNER, "null", "[]", "[]");

        publish(SUPERUSER, "\"" + owners.applicationId() + "\"",
                "[{\"name\": \"featured\", \"tagType\": \"COMMON\"}]", "[]");

        assertThat(tagsOf(owners.applicationId())).contains("COMMON:featured");
    }

    // ── 2. Linking somebody else's unpublished connector ───────────────────────────────────────

    @Test
    void publishingCannotLinkAnotherContributorsUnpublishedConnector() throws Exception {
        Published owners = publish(OWNER, "null", "[]", "[]");
        int draftConnector = connectorOf(owners.methodId());

        mockMvc.perform(post("/api/upload/integration")
                        .with(OTHER).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("null", "[]", "[]", "it-other", draftConnector)))
                .andExpect(status().is4xxClientError());

        assertThat(linksTo(draftConnector)).isEqualTo(1);
    }

    @Test
    void addingAConnectorCannotLinkAnotherContributorsUnpublishedConnector() throws Exception {
        Published owners = publish(OWNER, "null", "[]", "[]");
        int draftConnector = connectorOf(owners.methodId());
        Published others = publish(OTHER, "null", "[]", "[]");

        mockMvc.perform(post("/api/integration-method/{id}/1.0/connectors", others.methodId())
                        .with(OTHER).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"existingConnectorId\": " + draftConnector + "}"))
                .andExpect(status().is4xxClientError());

        assertThat(linksTo(draftConnector)).isEqualTo(1);
    }

    // ── 3. Triggering a build of somebody else's method ────────────────────────────────────────

    @Test
    void anotherContributorCannotTriggerABuild() throws Exception {
        Published owners = publish(OWNER, "null", "[]", "[]");
        Object[] version = jdbc.queryForObject("""
                select cv.id, cv.revision from connector_version cv
                where cv.connector_id = ?""",
                (rs, i) -> new Object[] {rs.getInt(1), rs.getString(2)}, connectorOf(owners.methodId()));

        mockMvc.perform(post("/api/upload/trigger-build/{id}", owners.methodId())
                        .with(OTHER).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"integrationMethodRevision": "1.0",
                                 "connectorVersionId": "%s", "connectorVersionRevision": "%s"}
                                """.formatted(version[0], version[1])))
                .andExpect(status().isForbidden());
    }

    // ── 4. The logo of an application that is not published yet ───────────────────────────────

    @Test
    void anotherContributorCannotReplaceTheLogoOfAnUnpublishedApplication() throws Exception {
        Published owners = publish(OWNER, "null", "[]", "[]");

        mockMvc.perform(multipart("/api/applications/{id}/logo", owners.applicationId())
                        .file(new MockMultipartFile("file", "logo.png", "image/png", PNG))
                        .with(OTHER).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void anotherContributorCannotDeleteTheLogoOfAnUnpublishedApplication() throws Exception {
        Published owners = publish(OWNER, "null", "[]", "[]");
        mockMvc.perform(multipart("/api/applications/{id}/logo", owners.applicationId())
                        .file(new MockMultipartFile("file", "logo.png", "image/png", PNG))
                        .with(OWNER).with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/applications/{id}/logo", owners.applicationId())
                        .with(OTHER).with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("select logo_path is not null from application where id = ?",
                Boolean.class, owners.applicationId())).isTrue();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    /** The smallest valid PNG: one transparent pixel. */
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    private record Published(UUID applicationId, UUID methodId) {
    }

    /** Publishes a method with a new connector, both maintained by the publisher. */
    private Published publish(RequestPostProcessor publisher, String applicationIdJson, String tagsJson,
                              String originsJson) throws Exception {
        String username = publisher == OWNER ? "it-owner" : publisher == OTHER ? "it-other" : "it-admin";
        String response = mockMvc.perform(post("/api/upload/integration")
                        .with(publisher).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(applicationIdJson, tagsJson, originsJson, username, null)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String[] ids = response.split("\\|");
        UUID applicationId = UUID.fromString(ids[0]);
        if (!applicationIds.contains(applicationId)) {
            applicationIds.add(applicationId);
        }
        return new Published(applicationId, UUID.fromString(ids[1]));
    }

    private String publishBody(String applicationIdJson, String tagsJson, String originsJson, String username,
                               Integer existingConnectorId) {
        Integer midpointVersion = jdbc.queryForObject("select min(id) from midpoint_version", Integer.class);
        Integer methodType = jdbc.queryForObject("select min(id) from integration_method_type", Integer.class);
        String maintainer = "{\"category\": \"USER\", \"username\": \"" + username + "\"}";
        return """
                {"application": {"id": %s, "displayName": "%s", "description": "IT", "logo": null,
                                 "origins": %s, "tags": %s},
                 "integrationMethod": {"id": null, "displayName": "IT method", "revision": "1.0",
                                       "description": "IT", "limitations": "", "tutorial": "",
                                       "maintainer": %s, "typeIds": [%d],
                                       "midpointMinVersion": %d, "midpointMaxVersion": null,
                                       "appMinVersion": null, "appMaxVersion": null},
                 "connector": {"displayName": "IT connector", "description": "IT", "maintainer": %s,
                               "framework": "JAVA_BASED", "license": "APACHE_2", "ticketingSystemLink": null,
                               "projectHomepage": "https://example.com/it", "gitCloneUrl": "https://example.com/it.git",
                               "buildFramework": "MAVEN", "pathToProject": null,
                               "className": "com.example.it.Endpoint%s", "version": "1.0.0", "commitTag": "v1",
                               "bundleDisplayName": null, "existingConnectorId": %s,
                               "connectorMinVersion": "1.0.0", "connectorMaxVersion": null},
                 "files": [], "integrationMethodCapabilities": [], "connectorCapabilities": []}
                """.formatted(applicationIdJson, uniqueDisplayName("Endpoint"), originsJson, tagsJson,
                maintainer, methodType, midpointVersion, maintainer,
                UUID.randomUUID().toString().replace("-", ""), existingConnectorId);
    }

    private int connectorOf(UUID methodId) {
        return jdbc.queryForObject("""
                select connector_id from integration_method_connector
                where integ_method_id = ? and integ_method_revision = '1.0'""", Integer.class, methodId);
    }

    private int linksTo(int connectorId) {
        return jdbc.queryForObject("select count(*) from integration_method_connector where connector_id = ?",
                Integer.class, connectorId);
    }

    private List<String> tagsOf(UUID applicationId) {
        return jdbc.queryForList("""
                select t.tag_type || ':' || t.name from application_application_tag aat
                join application_tag t on t.id = aat.tag_id
                where aat.application_id = ? order by 1""", String.class, applicationId);
    }

    private List<Integer> originsOf(UUID applicationId) {
        return jdbc.queryForList("select country_id from application_origin where application_id = ? order by 1",
                Integer.class, applicationId);
    }
}
