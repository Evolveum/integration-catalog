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
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may add and remove the tutorial files of an integration method: whoever may edit the method
 * (its maintainer, their organization, a superuser), never another contributor - also when the
 * request is sent straight to the API rather than through the edit form.
 */
class TutorialPermissionIntegrationTest extends AbstractCatalogIntegrationTest {

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

    @Test
    void anotherContributorCannotUploadATutorialFile() throws Exception {
        UUID method = publishAsOwner();

        upload(method, OTHER, "intruder.txt", status().isForbidden());

        assertThat(tutorialFiles(method)).isEmpty();
    }

    @Test
    void anotherContributorCannotDeleteATutorialFile() throws Exception {
        UUID method = publishAsOwner();
        upload(method, OWNER, "guide.txt", status().isOk());

        mockMvc.perform(delete("/api/integration-method/{id}/1.0/tutorial/file", method)
                        .param("name", "guide.txt")
                        .with(OTHER).with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(tutorialFiles(method)).containsExactly("guide.txt");
    }

    @Test
    void anotherContributorCannotUploadThroughTheMethodWideEndpoint() throws Exception {
        UUID method = publishAsOwner();

        mockMvc.perform(multipart("/api/integration-methods/{id}/tutorial", method)
                        .file(file("intruder.txt"))
                        .with(OTHER).with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(tutorialFiles(method)).isEmpty();
    }

    @Test
    void ownerAndSuperuserMayUploadAndDelete() throws Exception {
        UUID method = publishAsOwner();

        upload(method, OWNER, "guide.txt", status().isOk());
        upload(method, SUPERUSER, "review-notes.txt", status().isOk());
        assertThat(tutorialFiles(method)).containsExactlyInAnyOrder("guide.txt", "review-notes.txt");

        mockMvc.perform(delete("/api/integration-method/{id}/1.0/tutorial/file", method)
                        .param("name", "review-notes.txt")
                        .with(OWNER).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/integration-method/{id}/1.0/tutorial/file", method)
                        .param("name", "guide.txt")
                        .with(SUPERUSER).with(csrf()))
                .andExpect(status().isOk());
        assertThat(tutorialFiles(method)).isEmpty();
    }

    @Test
    void ownerIsLockedOutWhileTheRevisionIsUnderReview() throws Exception {
        UUID method = publishAsOwner();
        upload(method, OWNER, "guide.txt", status().isOk());
        mockMvc.perform(post("/api/integration-method/{id}/1.0/start-review", method)
                        .with(SUPERUSER).with(csrf()))
                .andExpect(status().isOk());

        upload(method, OWNER, "late.txt", status().isConflict());
        mockMvc.perform(delete("/api/integration-method/{id}/1.0/tutorial/file", method)
                        .param("name", "guide.txt")
                        .with(OWNER).with(csrf()))
                .andExpect(status().isConflict());

        assertThat(tutorialFiles(method)).containsExactly("guide.txt");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private void upload(UUID method, RequestPostProcessor uploader, String fileName, ResultMatcher expected)
            throws Exception {
        mockMvc.perform(multipart("/api/integration-method/{id}/1.0/tutorial", method)
                        .file(file(fileName))
                        .with(uploader).with(csrf()))
                .andExpect(expected);
    }

    private static MockMultipartFile file(String fileName) {
        return new MockMultipartFile("file", fileName, MediaType.TEXT_PLAIN_VALUE, "IT tutorial".getBytes());
    }

    private List<String> tutorialFiles(UUID method) throws Exception {
        String json = mockMvc.perform(get("/api/integration-method/{id}/1.0/tutorial", method).with(OWNER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$");
    }

    /** Publishes a new application with one method revision 1.0, maintained by the owner alone. */
    private UUID publishAsOwner() throws Exception {
        Integer midpointVersion = jdbc.queryForObject("select min(id) from midpoint_version", Integer.class);
        Integer methodType = jdbc.queryForObject("select min(id) from integration_method_type", Integer.class);
        String maintainer = "{\"category\": \"USER\", \"username\": \"it-owner\"}";
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
                               "className": "com.example.it.Tutorial%s", "version": "1.0.0", "commitTag": "v1",
                               "bundleDisplayName": null, "existingConnectorId": null,
                               "connectorMinVersion": "1.0.0", "connectorMaxVersion": null},
                 "files": [], "integrationMethodCapabilities": [], "connectorCapabilities": []}
                """.formatted(uniqueDisplayName("Tutorial"), maintainer, methodType, midpointVersion, maintainer,
                UUID.randomUUID().toString().replace("-", ""));
        String response = mockMvc.perform(post("/api/upload/integration")
                        .with(OWNER).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String[] ids = response.split("\\|");
        applicationIds.add(UUID.fromString(ids[0]));
        return UUID.fromString(ids[1]);
    }
}
