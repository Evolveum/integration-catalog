/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.evolveum.midpoint.integration.catalog.gui.framework.AbstractGuiTest;
import com.evolveum.midpoint.integration.catalog.gui.framework.Ui;
import com.evolveum.midpoint.integration.catalog.gui.pages.ApplicationDetail;
import com.evolveum.midpoint.integration.catalog.gui.pages.ApprovalQueue;
import com.evolveum.midpoint.integration.catalog.gui.pages.Header;
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
    void adminPublishesMethodThenSavesAndUpgradesIt() {
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
        queue.approve(row, new ApprovalQueue.ManualBuildInfo(
                "e2e-" + runId + "-bundle",
                CONNECTOR_VERSION,
                CONNECTOR_CLASS,
                "https://example.com/e2e-" + runId + ".jar"));
        assertAnonymousVisitorSeesMethod(applicationUrl);

        // 5. Save, 6. Approve the change
        application.open(applicationUrl);
        application.openPublishedVersionDetails("v1");
        editForm.openFromMethodDetail("1.0");
        editForm.changeLimitations("Changed by the GUI test with Save.");
        editForm.save();
        approveAwaitingVersion(header, queue, "v1");

        // 7. Save as new version, 8. Approve the upgrade
        application.open(applicationUrl);
        application.openPublishedVersionDetails("v1");
        editForm.openFromMethodDetail("1.1");
        editForm.changeLimitations("Changed by the GUI test with Save as new version.");
        editForm.saveAsNewVersion();
        approveAwaitingVersion(header, queue, "v2");

        application.open(applicationUrl);
        application.expectPublishedVersion("v2");
    }

    private void approveAwaitingVersion(Header header, ApprovalQueue queue, String versionBadge) {
        header.openApprovalQueue();
        Locator row = queue.findAwaitingRow(methodName, versionBadge);
        queue.startReview(row);
        queue.approve(row);
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
