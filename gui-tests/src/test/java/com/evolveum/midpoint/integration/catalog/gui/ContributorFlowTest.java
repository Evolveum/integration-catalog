/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui;

import com.evolveum.midpoint.integration.catalog.gui.framework.AbstractGuiTest;
import com.evolveum.midpoint.integration.catalog.gui.pages.Header;
import com.evolveum.midpoint.integration.catalog.gui.pages.MethodEditForm;
import com.evolveum.midpoint.integration.catalog.gui.pages.MyIntegrationMethods;
import com.evolveum.midpoint.integration.catalog.gui.pages.PublishWizard;
import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.Test;

/**
 * Sections 3 and 4 of docs/guide-contributor-base-flows.adoc: publish, follow the method in "My
 * integration methods", change it while it awaits approval, and withdraw it, which also removes the
 * test data. Section 2 is in {@link RequestFlowTest}.
 */
class ContributorFlowTest extends AbstractGuiTest {

    private final long runId = System.currentTimeMillis();
    private final String methodName = "e2e_" + runId + "_contributor_method";

    @Test
    void contributorPublishesChangesAndWithdrawsMethod() {
        Header header = new Header(page);
        MyIntegrationMethods myMethods = new MyIntegrationMethods(ui);
        MethodEditForm editForm = new MethodEditForm(ui);

        header.signIn(CONTRIBUTOR);
        new PublishWizard(ui).publishNewMethod("e2e_" + runId + "_contributor_app", methodName,
                "e2e_" + runId + "_contributor_connector", "1.0.0", "com.evolveum.e2e.E2eConnector");

        // Saving a version that awaits approval changes it in place: still one row, 1.0 becomes 1.1.
        header.openMyIntegrationMethods();
        myMethods.editAndUpgrade(myMethods.findAwaitingRow(methodName, "v1"));
        editForm.expectVersion("1.0");
        editForm.changeLimitations("Changed by the GUI test while awaiting approval.");
        editForm.save();

        header.openMyIntegrationMethods();
        myMethods.editAndUpgrade(myMethods.findAwaitingRow(methodName, "v1"));
        editForm.expectVersion("1.1");

        header.openMyIntegrationMethods();
        Locator row = myMethods.findAwaitingRow(methodName, "v1");
        myMethods.cancelRequest(row);
        header.expectToast("Request cancelled");
        myMethods.expectNoMethod(methodName);
    }
}
