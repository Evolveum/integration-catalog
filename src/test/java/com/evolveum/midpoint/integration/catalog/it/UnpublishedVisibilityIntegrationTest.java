/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.it;

import com.evolveum.midpoint.integration.catalog.security.CatalogRole;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reads are open to anyone, so the API itself has to hold back a revision that is not approved yet:
 * everyone sees ACTIVE revisions, a revision in review or rejected only whoever may edit it
 * (author, maintainer, their organization) and superusers.
 */
class UnpublishedVisibilityIntegrationTest extends AbstractCatalogIntegrationTest {

    private static final RequestPostProcessor OWNER = user("it-owner", CatalogRole.INDIVIDUAL_CONTRIBUTOR);
    private static final RequestPostProcessor OTHER = user("it-other", CatalogRole.INDIVIDUAL_CONTRIBUTOR);
    private static final RequestPostProcessor SUPERUSER = user("it-admin", CatalogRole.SUPERUSER);
    /** Sends the request without signing in. */
    private static final RequestPostProcessor ANONYMOUS = request -> request;

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

    @Test
    void anonymousCannotSeeAnUnapprovedRevision() throws Exception {
        Published owners = publishWithTutorial();

        assertHidden(owners, ANONYMOUS);
    }

    @Test
    void anotherContributorCannotSeeAnUnapprovedRevision() throws Exception {
        Published owners = publishWithTutorial();

        assertHidden(owners, OTHER);
    }

    @Test
    void ownerAndSuperuserSeeTheUnapprovedRevision() throws Exception {
        Published owners = publishWithTutorial();

        assertVisible(owners, OWNER);
        assertVisible(owners, SUPERUSER);
    }

    @Test
    void othersSeeOnlyTheActiveMethodsOfAnApplication() throws Exception {
        Published active = publish(OWNER, null);
        jdbc.update("update integration_method set lifecycle_state = 'ACTIVE' where id = ?", active.methodId());
        Published inReview = publish(OWNER, active.applicationId());

        for (RequestPostProcessor viewer : List.of(ANONYMOUS, OTHER)) {
            assertThat(methodIdsOnDetail(active.applicationId(), viewer))
                    .contains(active.methodId().toString())
                    .doesNotContain(inReview.methodId().toString());
            assertThat(methodIdsOfImplementations(active.applicationId(), viewer))
                    .contains(active.methodId().toString())
                    .doesNotContain(inReview.methodId().toString());
        }
        assertThat(methodIdsOnDetail(active.applicationId(), OWNER))
                .contains(active.methodId().toString(), inReview.methodId().toString());
    }

    @Test
    void applicationSearchFindsTheApplication() throws Exception {
        Published published = publish(OWNER, null);
        String displayName = jdbc.queryForObject("select display_name from application where id = ?",
                String.class, published.applicationId());

        String json = perform(post("/api/applications/search/{size}/{page}", 50, 0).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"keyword\": \"" + displayName + "\"}"), ANONYMOUS, 200);
        List<String> ids = JsonPath.read(json, "$.content[*].id");

        assertThat(ids).containsExactly(published.applicationId().toString());
    }

    @Test
    void othersFindOnlyTheActiveMethodsBySearch() throws Exception {
        Published active = publish(OWNER, null);
        jdbc.update("update integration_method set lifecycle_state = 'ACTIVE' where id = ?", active.methodId());
        Published inReview = publish(OWNER, active.applicationId());

        for (RequestPostProcessor viewer : List.of(ANONYMOUS, OTHER)) {
            assertThat(methodIdsBySearch(active.applicationId(), viewer))
                    .containsExactly(active.methodId().toString());
        }
        assertThat(methodIdsBySearch(active.applicationId(), OWNER))
                .containsExactlyInAnyOrder(active.methodId().toString(), inReview.methodId().toString());
    }

    @Test
    void anonymousIsRefusedTheSupportTicket() throws Exception {
        Published owners = publish(OWNER, null);

        mockMvc.perform(get("/api/integration-method/{id}/1.0/support-ticket", owners.methodId()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void aBuildCannotBeTriggeredForAnotherMethodsConnector() throws Exception {
        Published owners = publish(OWNER, null);
        Published others = publish(OTHER, null);
        Object[] ownersVersion = jdbc.queryForObject("""
                select cv.id, cv.revision from connector_version cv
                join integration_method_connector imc on imc.connector_id = cv.connector_id
                where imc.integ_method_id = ?""",
                (rs, i) -> new Object[] {rs.getInt(1), rs.getString(2)}, owners.methodId());

        // OTHER may build their own method, but names the owner's connector version in the body.
        mockMvc.perform(post("/api/upload/trigger-build/{id}", others.methodId())
                        .with(OTHER).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"integrationMethodRevision": "1.0",
                                 "connectorVersionId": "%s", "connectorVersionRevision": "%s"}
                                """.formatted(ownersVersion[0], ownersVersion[1])))
                .andExpect(status().is4xxClientError());
    }

    // ── Assertions ──────────────────────────────────────────────────────────────────────────────

    private void assertHidden(Published method, RequestPostProcessor viewer) throws Exception {
        assertThat(methodIdsOnDetail(method.applicationId(), viewer)).doesNotContain(method.methodId().toString());
        assertThat(methodIdsOfImplementations(method.applicationId(), viewer))
                .doesNotContain(method.methodId().toString());
        assertThat(connectorsOf(method.methodId(), viewer)).isEmpty();
        perform(get("/api/integration-method/{id}/1.0/tutorial", method.methodId()), viewer, 404);
        perform(get("/api/integration-method/{id}/1.0/tutorial/file", method.methodId()).param("name", "guide.txt"),
                viewer, 404);
        perform(get("/api/integration-method/{id}/1.0/bundle", method.methodId()), viewer, 404);
    }

    private void assertVisible(Published method, RequestPostProcessor viewer) throws Exception {
        assertThat(methodIdsOnDetail(method.applicationId(), viewer)).contains(method.methodId().toString());
        assertThat(methodIdsOfImplementations(method.applicationId(), viewer)).contains(method.methodId().toString());
        assertThat(connectorsOf(method.methodId(), viewer)).isNotEmpty();
        perform(get("/api/integration-method/{id}/1.0/tutorial", method.methodId()), viewer, 200);
        perform(get("/api/integration-method/{id}/1.0/tutorial/file", method.methodId()).param("name", "guide.txt"),
                viewer, 200);
        perform(get("/api/integration-method/{id}/1.0/bundle", method.methodId()), viewer, 200);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private record Published(UUID applicationId, UUID methodId) {
    }

    private String perform(MockHttpServletRequestBuilder request, RequestPostProcessor viewer, int expectedStatus)
            throws Exception {
        return mockMvc.perform(request.with(viewer))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    private List<String> methodIdsOnDetail(UUID applicationId, RequestPostProcessor viewer) throws Exception {
        String json = perform(get("/api/applications/{id}", applicationId), viewer, 200);
        List<String> ids = JsonPath.read(json, "$.integrationMethods[*].id");
        return ids;
    }

    private List<String> methodIdsOfImplementations(UUID applicationId, RequestPostProcessor viewer) throws Exception {
        String json = perform(get("/api/applications/{id}/implementations", applicationId), viewer, 200);
        List<String> ids = JsonPath.read(json, "$[*].id");
        return ids;
    }

    private List<String> methodIdsBySearch(UUID applicationId, RequestPostProcessor viewer) throws Exception {
        String json = perform(post("/api/integration-methods/search/{size}/{page}", 50, 0).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"applicationId\": \"" + applicationId + "\"}"), viewer, 200);
        List<String> ids = JsonPath.read(json, "$.content[*].id");
        return ids;
    }

    private List<Object> connectorsOf(UUID methodId, RequestPostProcessor viewer) throws Exception {
        String json = perform(get("/api/integration-method/{id}/1.0/connectors", methodId), viewer, 200);
        return JsonPath.read(json, "$");
    }

    /** A method in review, with one tutorial file, in a new application of the owner's. */
    private Published publishWithTutorial() throws Exception {
        Published published = publish(OWNER, null);
        mockMvc.perform(multipart("/api/integration-method/{id}/1.0/tutorial", published.methodId())
                        .file(new MockMultipartFile("file", "guide.txt", MediaType.TEXT_PLAIN_VALUE,
                                "IT tutorial".getBytes()))
                        .with(OWNER).with(csrf()))
                .andExpect(status().isOk());
        return published;
    }

    /** Publishes a method with a new connector, both maintained by the publisher; into a new application if null. */
    private Published publish(RequestPostProcessor publisher, UUID applicationId) throws Exception {
        String username = publisher == OWNER ? "it-owner" : "it-other";
        Integer midpointVersion = jdbc.queryForObject("select min(id) from midpoint_version", Integer.class);
        Integer methodType = jdbc.queryForObject("select min(id) from integration_method_type", Integer.class);
        String maintainer = "{\"category\": \"USER\", \"username\": \"" + username + "\"}";
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
                               "className": "com.example.it.Visibility%s", "version": "1.0.0", "commitTag": "v1",
                               "bundleDisplayName": null, "existingConnectorId": null,
                               "connectorMinVersion": "1.0.0", "connectorMaxVersion": null},
                 "files": [], "integrationMethodCapabilities": [], "connectorCapabilities": []}
                """.formatted(applicationId == null ? "null" : "\"" + applicationId + "\"",
                uniqueDisplayName("Visibility"), maintainer, methodType, midpointVersion, maintainer,
                UUID.randomUUID().toString().replace("-", ""));
        String response = mockMvc.perform(post("/api/upload/integration")
                        .with(publisher).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String[] ids = response.split("\\|");
        UUID publishedApplication = UUID.fromString(ids[0]);
        if (!applicationIds.contains(publishedApplication)) {
            applicationIds.add(publishedApplication);
        }
        return new Published(publishedApplication, UUID.fromString(ids[1]));
    }
}
