/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evolveum.midpoint.integration.catalog.gui.framework.AbstractGuiTest;
import com.evolveum.midpoint.integration.catalog.gui.framework.Ui;
import com.evolveum.midpoint.integration.catalog.gui.pages.ApplicationDetail;
import com.evolveum.midpoint.integration.catalog.gui.pages.ApprovalQueue;
import com.evolveum.midpoint.integration.catalog.gui.pages.Connectors;
import com.evolveum.midpoint.integration.catalog.gui.pages.Header;
import com.evolveum.midpoint.integration.catalog.gui.pages.MethodDetail;
import com.evolveum.midpoint.integration.catalog.gui.pages.MethodEditForm;
import com.evolveum.midpoint.integration.catalog.gui.pages.PublishWizard;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

/**
 * The superuser base flow of docs/guide-admin-base-flows.adoc, sections 1 and 3 to 8, as one test
 * because each step works on what the previous one created. Section 2 is in {@link RequestFlowTest}.
 * Prerequisites are in gui-tests/README.md.
 */
class AdminFlowTest extends AbstractGuiTest {

    private static final String CONNECTOR_VERSION = "1.0.0";
    private static final String CONNECTOR_CLASS = "com.evolveum.e2e.E2eConnector";

    private final long runId = System.currentTimeMillis();
    private final String applicationName = "e2e_" + runId + "_app";
    private final String methodName = "e2e_" + runId + "_method";
    private final String connectorName = "e2e_" + runId + "_connector";

    @Test
    void adminPublishesMethodThenChangesItsConnectorWithSaveAndUpgrade() {
        Header header = new Header(page);
        ApprovalQueue queue = new ApprovalQueue(ui, SUPPORT_PORTAL);
        ApplicationDetail application = new ApplicationDetail(page);
        MethodEditForm editForm = new MethodEditForm(ui);

        header.signIn(SUPERUSER);

        // 3. Publish, 4. Approve
        new PublishWizard(ui).publishNewMethod(
                applicationName, methodName, connectorName, CONNECTOR_VERSION, CONNECTOR_CLASS);
        header.openApprovalQueue();
        Locator row = queue.findAwaitingRow(methodName, "v1");
        String applicationUrl = queue.applicationUrl(row);
        queue.startReview(row);
        // With a download link; ExistingApplicationFlowTest approves a connector without one.
        queue.approve(row, buildInfo(CONNECTOR_VERSION));
        assertAnonymousVisitorSeesMethod(applicationUrl);

        // 5. Save, 6. Approve the change: the connector description, then its version
        openEditForm(application, applicationUrl, "v1", "1.0");
        editForm.changeConnectorDescription("Described by the GUI test with Save.");
        editForm.save();
        approveAwaitingVersion(header, queue, "v1", null);
        openPublishedVersion(application, applicationUrl, "v1")
                .expectConnectorDescription("Described by the GUI test with Save.");

        openEditForm(application, applicationUrl, "v1", "1.1");
        editForm.changeConnectorVersion("1.0.1");
        editForm.save();
        approveAwaitingVersion(header, queue, "v1", buildInfo("1.0.1"));
        openPublishedVersion(application, applicationUrl, "v1").expectConnectorVersion("1.0.1");

        // 7. Save as new version, 8. Approve the upgrade: the connector description, then its version
        openEditForm(application, applicationUrl, "v1", "1.2");
        editForm.changeConnectorDescription("Described by the GUI test with Save as new version.");
        editForm.saveAsNewVersion();
        approveAwaitingVersion(header, queue, "v2", null);
        openPublishedVersion(application, applicationUrl, "v2")
                .expectConnectorDescription("Described by the GUI test with Save as new version.");

        openEditForm(application, applicationUrl, "v2", "2.0");
        editForm.changeConnectorVersion("1.0.2");
        editForm.saveAsNewVersion();
        approveAwaitingVersion(header, queue, "v3", buildInfo("1.0.2"));
        MethodDetail latest = openPublishedVersion(application, applicationUrl, "v3");
        latest.expectConnectorVersion("1.0.2");

        // The test's download link points nowhere, so the bundle reports the missing JAR instead.
        MethodDetail.DownloadedBundle bundle = latest.download();
        assertEquals(applicationName + " (" + methodName + ").zip", bundle.fileName());
        assertTrue(bundle.entries().contains("ERROR.txt"), "entries: " + bundle.entries());
        assertFalse(bundle.hasConnectorJar(), "entries: " + bundle.entries());

        // Every edit worked on a copy of the connector; none of the copies may stay behind.
        header.openAllConnectors();
        new Connectors(ui).expectSingleConnector(connectorName, "1.0.0", "1.0.1", "1.0.2");
    }

    private ApprovalQueue.ManualBuildInfo buildInfo(String connectorVersion) {
        return new ApprovalQueue.ManualBuildInfo("e2e-" + runId + "-bundle", connectorVersion, CONNECTOR_CLASS,
                "https://example.com/e2e-" + runId + "-" + connectorVersion + ".jar");
    }

    private MethodDetail openPublishedVersion(ApplicationDetail application, String applicationUrl, String versionBadge) {
        application.open(applicationUrl);
        application.openPublishedVersionDetails(versionBadge);
        return new MethodDetail(ui);
    }

    private void openEditForm(ApplicationDetail application, String applicationUrl, String versionBadge, String version) {
        openPublishedVersion(application, applicationUrl, versionBadge);
        new MethodEditForm(ui).openFromMethodDetail(version);
    }

    /** @param build {@code null} when every connector already has its build data */
    private void approveAwaitingVersion(Header header, ApprovalQueue queue, String versionBadge,
            ApprovalQueue.ManualBuildInfo build) {
        header.openApprovalQueue();
        Locator row = queue.findAwaitingRow(methodName, versionBadge);
        queue.startReview(row);
        if (build == null) {
            queue.approve(row);
        } else {
            queue.approve(row, build);
        }
    }

    private void assertAnonymousVisitorSeesMethod(String applicationUrl) {
        try (BrowserContext anonymous = newContext()) {
            Page anonymousPage = anonymous.newPage();
            anonymousPage.navigate(applicationUrl);
            Ui anonymousUi = new Ui(anonymousPage);
            anonymousUi.expectVisible("header-login");
            assertThat(anonymousUi.element("application-method-title")
                    .filter(new Locator.FilterOptions().setHasText(methodName))).isVisible();
        }
    }
}
