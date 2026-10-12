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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A connector's way through edits of the integration methods that use it: the copy-on-write clone
 * an edit makes, its build and the approval that folds it back or lets it replace the original.
 * Covers the version and bundle rows, where a clone once broke the unique (bundle, version) key
 * at build time and again at approval.
 */
class ConnectorVersionLifecycleIntegrationTest extends AbstractCatalogIntegrationTest {

    private static final RequestPostProcessor SUPERUSER = user("it-admin", CatalogRole.SUPERUSER);
    private static final String CLASS_NAME = "com.example.it.RestConnector";
    private static final String GIT_CLONE_URL = "https://example.com/it/rest.git";

    @Autowired
    private JdbcTemplate jdbc;

    /** Bundles the tests created; connectors and their version rows go with them. */
    private final List<Integer> bundleIds = new ArrayList<>();
    private final List<UUID> applicationIds = new ArrayList<>();

    @AfterEach
    void deleteConnectors() {
        for (Integer bundleId : bundleIds) {
            jdbc.update("delete from connector_bundle where id = ?", bundleId);
        }
        // Through the database's cascades: JPA cannot delete an application that has methods.
        for (UUID applicationId : applicationIds) {
            jdbc.update("delete from application where id = ?", applicationId);
        }
    }

    @Test
    void metadataEditFoldsBackIntoTheOriginal() throws Exception {
        Published im = publishAndApprove("1.0.0");
        int original = connectorOf(im.methodId(), "1.0");

        String revision = editMethod(im, "1.0", true);
        editConnector(im.methodId(), revision, original, "IT renamed connector", "1.0.0", "1.0.0");
        int clone = connectorOf(im.methodId(), revision);
        assertThat(clone).isNotEqualTo(original);

        approve(im.methodId(), revision);

        assertThat(connectorOf(im.methodId(), revision)).isEqualTo(original);
        assertThat(jdbc.queryForObject("select display_name from connector where id = ?", String.class, original))
                .isEqualTo("IT renamed connector");
        assertThat(connectorExists(clone)).isFalse();
        assertThat(versionsOfBundle(bundleOf(original))).containsExactly("1.0.0");
    }

    @Test
    void versionChangeKeepsTheCloneApartUntilApprovalReplacesTheOriginal() throws Exception {
        Published im = publishAndApprove("1.0.0");
        int original = connectorOf(im.methodId(), "1.0");
        int originalBundle = bundleOf(original);

        String revision = editMethod(im, "1.0", true);
        editConnector(im.methodId(), revision, original, null, "1.1.0", "1.0.0");
        int clone = connectorOf(im.methodId(), revision);
        assertThat(noEmptyBundleVersions(bundleOf(clone))).isTrue();

        // The build names the bundle like the published original: the clone must not be merged into it.
        fillBuild(im.methodId(), revision, im.bundleName(), "1.1.0");
        assertThat(bundleOf(clone)).isNotEqualTo(originalBundle);

        approve(im.methodId(), revision);

        assertThat(connectorOf(im.methodId(), revision)).isEqualTo(clone);
        assertThat(connectorExists(original)).isFalse();
        assertThat(bundleExists(originalBundle)).isFalse();
        assertThat(versionsOfBundle(bundleOf(clone))).containsExactly("1.0.0", "1.1.0");
        assertThat(noEmptyBundleVersions(bundleOf(clone))).isTrue();
    }

    @Test
    void versionChangeOfAnUnbuiltVersionLeavesNoEmptyRowBehind() throws Exception {
        // Without an artifact URL the published version stays unbuilt, so the edit's copy of it is
        // dropped when the version changes - together with its bundle-version row.
        Published im = publishAndApprove("1.0.0", true);
        int original = connectorOf(im.methodId(), "1.0");

        String revision = editMethod(im, "1.0", true);
        editConnector(im.methodId(), revision, original, null, "1.1.0", "1.0.0");
        int clone = connectorOf(im.methodId(), revision);
        assertThat(versionsOfBundle(bundleOf(clone))).containsExactly("1.1.0");
        assertThat(noEmptyBundleVersions(bundleOf(clone))).isTrue();

        fillBuild(im.methodId(), revision, im.bundleName(), "1.1.0", true);
        approve(im.methodId(), revision);

        assertThat(versionsOfBundle(bundleOf(connectorOf(im.methodId(), revision))))
                .containsExactly("1.0.0", "1.1.0");
    }

    @Test
    void newMajorVersionKeepsTheOlderRevisionPublishedOnTheSameConnector() throws Exception {
        Published im = publishAndApprove("1.0.0");
        int original = connectorOf(im.methodId(), "1.0");

        String major = editMethod(im, "1.0", false);
        assertThat(major).isEqualTo("2.0");
        editConnector(im.methodId(), major, original, null, "2.0.0", "1.0.0");
        fillBuild(im.methodId(), major, im.bundleName(), "2.0.0");
        approve(im.methodId(), major);

        int connector = connectorOf(im.methodId(), major);
        assertThat(connectorOf(im.methodId(), "1.0")).isEqualTo(connector);
        assertThat(stateOf(im.methodId(), "1.0")).isEqualTo("ACTIVE");
        assertThat(stateOf(im.methodId(), major)).isEqualTo("ACTIVE");
        assertThat(versionsOfBundle(bundleOf(connector))).containsExactly("1.0.0", "2.0.0");
    }

    @Test
    void versionChangeRaisesAMaxVersionBelowIt() throws Exception {
        Published im = publishAndApprove("1.0.0");
        int original = connectorOf(im.methodId(), "1.0");

        String revision = editMethod(im, "1.0", true);
        mockMvc.perform(put("/api/integration-method/{id}/{rev}/connectors/{cid}/compatibility",
                        im.methodId(), revision, original)
                        .with(SUPERUSER).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"connectorVersionFrom\": \"1.0.0\", \"connectorVersionTo\": \"1.0.0\"}"))
                .andExpect(status().isOk());
        editConnector(im.methodId(), revision, original, null, "1.1.0", "1.0.0");

        Map<String, Object> link = jdbc.queryForMap("""
                select connector_minversion, connector_maxversion from integration_method_connector
                where integ_method_id = ? and integ_method_revision = ?""", im.methodId(), revision);
        assertThat(link.get("connector_minversion")).isEqualTo("1.0.0");
        assertThat(link.get("connector_maxversion")).isEqualTo("1.1.0");
    }

    // ── Flow steps, as the UI sends them ────────────────────────────────────────────────────────

    private record Published(UUID applicationId, UUID methodId, String bundleName) {
    }

    private Published publishAndApprove(String connectorVersion) throws Exception {
        return publishAndApprove(connectorVersion, false);
    }

    /** Publishes a new application with one method and a new connector, builds it and approves it. */
    private Published publishAndApprove(String connectorVersion, boolean artifactUrlless) throws Exception {
        Integer midpointVersion = jdbc.queryForObject("select min(id) from midpoint_version", Integer.class);
        Integer methodType = jdbc.queryForObject("select min(id) from integration_method_type", Integer.class);
        String body = """
                {"application": {"id": null, "displayName": "%s", "description": "IT", "logo": null,
                                 "origins": [], "tags": []},
                 "integrationMethod": {"id": null, "displayName": "IT method", "revision": "1.0",
                                       "description": "IT", "limitations": "", "tutorial": "",
                                       "maintainer": {"category": "COMMUNITY"}, "typeIds": [%d],
                                       "midpointMinVersion": %d, "midpointMaxVersion": null,
                                       "appMinVersion": null, "appMaxVersion": null},
                 "connector": {"displayName": "IT connector", "description": "IT",
                               "maintainer": {"category": "COMMUNITY"}, "framework": "JAVA_BASED",
                               "license": "APACHE_2", "ticketingSystemLink": null,
                               "projectHomepage": "https://example.com/it", "gitCloneUrl": "%s",
                               "buildFramework": "MAVEN", "pathToProject": null, "className": "%s",
                               "version": "%s", "commitTag": "v1", "bundleDisplayName": null,
                               "existingConnectorId": null, "connectorMinVersion": "%s",
                               "connectorMaxVersion": null},
                 "files": [], "integrationMethodCapabilities": [], "connectorCapabilities": []}
                """.formatted(uniqueDisplayName("Connector flow"), methodType, midpointVersion,
                GIT_CLONE_URL, CLASS_NAME, connectorVersion, connectorVersion);
        String response = mockMvc.perform(post("/api/upload/integration")
                        .with(SUPERUSER).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String[] ids = response.split("\\|");
        applicationIds.add(UUID.fromString(ids[0]));
        UUID methodId = UUID.fromString(ids[1]);
        bundleIds.add(bundleOf(connectorOf(methodId, "1.0")));

        String bundleName = "it-" + UUID.randomUUID().toString().substring(0, 8);
        fillBuild(methodId, "1.0", bundleName, connectorVersion, artifactUrlless);
        approve(methodId, "1.0");
        return new Published(UUID.fromString(ids[0]), methodId, bundleName);
    }

    /** "Save" (minor) or "Save as new version" (major) of the edit form; returns the new revision. */
    private String editMethod(Published im, String revision, boolean minorBump) throws Exception {
        String body = """
                {"displayName": "IT method", "description": "IT", "limitations": "", "tutorial": "",
                 "typeIds": null, "capabilities": [], "removeFile": false, "minorBump": %s,
                 "midpointMinVersion": null, "midpointMaxVersion": null,
                 "appMinVersion": null, "appMaxVersion": null, "maintainer": null}
                """.formatted(minorBump);
        return mockMvc.perform(put("/api/integration-method/{id}/{rev}", im.methodId(), revision)
                        .with(SUPERUSER).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** The connector edit the form sends after the new revision exists. */
    private void editConnector(UUID methodId, String revision, int connectorId, String displayName,
                               String version, String baseVersion) throws Exception {
        String body = """
                {"displayName": "%s", "description": "IT", "maintainer": {"category": "COMMUNITY"},
                 "projectHomepage": "https://example.com/it", "supportPortal": null, "bundleDisplayName": null,
                 "version": "%s", "baseVersion": "%s", "commitTag": "v1", "pathToProject": null,
                 "buildFramework": "MAVEN", "className": "%s", "connectorCapabilities": [],
                 "license": "APACHE_2", "gitCloneUrl": "%s"}
                """.formatted(displayName != null ? displayName : "IT connector", version, baseVersion,
                CLASS_NAME, GIT_CLONE_URL);
        mockMvc.perform(put("/api/integration-method/{id}/{rev}/connectors/{cid}", methodId, revision, connectorId)
                        .with(SUPERUSER).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        bundleIds.add(bundleOf(connectorOf(methodId, revision)));
    }

    private void fillBuild(UUID methodId, String revision, String bundleName, String version) throws Exception {
        fillBuild(methodId, revision, bundleName, version, false);
    }

    /** The manual fill: the build information for the method's version row that has none yet. */
    private void fillBuild(UUID methodId, String revision, String bundleName, String version,
                           boolean artifactUrlless) throws Exception {
        Map<String, Object> unbuilt = jdbc.queryForMap("""
                select cbv.id, cbv.revision from connector_bundle_version cbv
                join connector c on c.connector_bundle_id = cbv.connector_bundle_id
                join integration_method_connector imc on imc.connector_id = c.id
                where imc.integ_method_id = ? and imc.integ_method_revision = ?
                  and cbv.artifact_url is null and cbv.bundle_version = ?""", methodId, revision, version);
        String body = """
                {"connectorBundle": "%s", "connectorVersion": "%s", "integrationMethodRevision": "%s",
                 "downloadLink": %s, "connectorClass": "%s",
                 "connectorBundleVersionId": "%s", "connectorBundleVersionRevision": "%s",
                 "artifactUrlless": %s}
                """.formatted(bundleName, version, revision,
                artifactUrlless ? "null" : "\"https://example.com/it/rest-" + version + ".jar\"",
                CLASS_NAME, unbuilt.get("id"), unbuilt.get("revision"), artifactUrlless);
        mockMvc.perform(post("/api/upload/continue/{id}", methodId)
                        .with(SUPERUSER).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private void approve(UUID methodId, String revision) throws Exception {
        mockMvc.perform(post("/api/integration-method/{id}/{rev}/start-review", methodId, revision)
                        .with(SUPERUSER).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/integration-method/{id}/{rev}/approve", methodId, revision)
                        .with(SUPERUSER).with(csrf()))
                .andExpect(status().isOk());
    }

    // ── Reading the rows back ───────────────────────────────────────────────────────────────────

    private int connectorOf(UUID methodId, String revision) {
        return jdbc.queryForObject("""
                select connector_id from integration_method_connector
                where integ_method_id = ? and integ_method_revision = ?""", Integer.class, methodId, revision);
    }

    private int bundleOf(int connectorId) {
        return jdbc.queryForObject("select connector_bundle_id from connector where id = ?", Integer.class, connectorId);
    }

    private String stateOf(UUID methodId, String revision) {
        return jdbc.queryForObject("""
                select lifecycle_state::text from integration_method where id = ? and revision = ?""",
                String.class, methodId, revision);
    }

    /** The bundle's versions in order; a version listed twice is the duplicate these tests guard against. */
    private List<String> versionsOfBundle(int bundleId) {
        return jdbc.queryForList("""
                select bundle_version from connector_bundle_version where connector_bundle_id = ?
                order by bundle_version""", String.class, bundleId);
    }

    private boolean noEmptyBundleVersions(int bundleId) {
        Integer empty = jdbc.queryForObject("""
                select count(*) from connector_bundle_version cbv where cbv.connector_bundle_id = ?
                  and not exists (select 1 from connector_version cv
                                  where cv.connector_bundle_version_id = cbv.id
                                    and cv.connector_bundle_version_revision = cbv.revision)""",
                Integer.class, bundleId);
        return empty != null && empty == 0;
    }

    private boolean connectorExists(int connectorId) {
        return jdbc.queryForObject("select count(*) from connector where id = ?", Integer.class, connectorId) > 0;
    }

    private boolean bundleExists(int bundleId) {
        return jdbc.queryForObject("select count(*) from connector_bundle where id = ?", Integer.class, bundleId) > 0;
    }
}
