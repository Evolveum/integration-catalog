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
import com.evolveum.midpoint.integration.catalog.gui.pages.MyIntegrationMethods;
import com.evolveum.midpoint.integration.catalog.gui.pages.PublishWizard;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

/**
 * A contributor publishes a new application with a new connector and changes it before approval;
 * a superuser approves it with a connector without an artifact URL. The contributor then publishes
 * a second method to that application, reusing the connector, which needs no build data to approve.
 */
class ExistingApplicationFlowTest extends AbstractGuiTest {

    private static final String CONNECTOR_VERSION = "1.0.0";
    private static final String CONNECTOR_CLASS = "com.evolveum.e2e.E2eConnector";

    private final long runId = System.currentTimeMillis();
    private final String applicationName = "e2e_" + runId + "_existing_app";
    private final String firstMethodName = "e2e_" + runId + "_first_method";
    private final String secondMethodName = "e2e_" + runId + "_second_method";
    private final String connectorName = "e2e_" + runId + "_urlless_connector";

    @Test
    void contributorEditsBeforeApprovalThenReusesApplicationAndConnector() {
        Header header = new Header(page);
        PublishWizard wizard = new PublishWizard(ui);
        MyIntegrationMethods myMethods = new MyIntegrationMethods(ui);
        MethodEditForm editForm = new MethodEditForm(ui);

        header.signIn(CONTRIBUTOR);
        wizard.publishNewMethod(applicationName, firstMethodName, connectorName, CONNECTOR_VERSION, CONNECTOR_CLASS);
        header.openMyIntegrationMethods();
        myMethods.editAndUpgrade(myMethods.findAwaitingRow(firstMethodName, "v1"));
        editForm.expectVersion("1.0");
        editForm.changeLimitations("Changed by the GUI test before approval.");
        editForm.save();

        try (BrowserContext superuserContext = newContext()) {
            Page superuserPage = superuserContext.newPage();
            Header superuserHeader = new Header(superuserPage);
            ApprovalQueue queue = new ApprovalQueue(new Ui(superuserPage), SUPPORT_PORTAL);
            superuserHeader.signIn(SUPERUSER);

            superuserHeader.openApprovalQueue();
            Locator row = queue.findAwaitingRow(firstMethodName, "v1");
            String applicationUrl = queue.applicationUrl(row);
            queue.startReview(row);
            queue.approve(row, new ApprovalQueue.ManualBuildInfo(
                    "e2e-" + runId + "-urlless-bundle", CONNECTOR_VERSION, CONNECTOR_CLASS, null));
            // Leaving the queue also makes the next visit load it again; the menu link alone would not.
            ApplicationDetail application = new ApplicationDetail(superuserPage);
            application.open(applicationUrl);
            application.openPublishedVersionDetails("v1");
            MethodDetail.DownloadedBundle bundle = new MethodDetail(new Ui(superuserPage)).download();
            assertEquals(applicationName + " (" + firstMethodName + ").zip", bundle.fileName());
            assertFalse(bundle.hasConnectorJar(), "a connector without an artifact URL has no JAR: " + bundle.entries());
            assertTrue(bundle.entries().contains("WARNING.txt"), "entries: " + bundle.entries());

            page.navigate("/applications");
            wizard.open();
            wizard.selectExistingApplication(applicationName);
            wizard.selectFirstMethodType();
            wizard.defineMethod(secondMethodName);
            wizard.selectExistingConnector(connectorName);
            wizard.setCompatibility(CONNECTOR_VERSION);
            wizard.submit();

            superuserHeader.openApprovalQueue();
            row = queue.findAwaitingRow(secondMethodName, "v1");
            queue.startReview(row);
            queue.approve(row);

            expectPublishedMethod(superuserPage, applicationUrl, secondMethodName);

            // Reusing the connector must not have copied it.
            superuserHeader.openAllConnectors();
            Connectors connectors = new Connectors(new Ui(superuserPage));
            connectors.expectSingleConnector(connectorName, CONNECTOR_VERSION);
            connectors.expectUsage(connectorName, "Used by 2 integration methods");
        }
    }

    private static void expectPublishedMethod(Page page, String applicationUrl, String methodName) {
        page.navigate(applicationUrl);
        assertThat(new Ui(page).element("application-method-title")
                .filter(new Locator.FilterOptions().setHasText(methodName))).isVisible();
    }
}
