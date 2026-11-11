/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui;

import com.evolveum.midpoint.integration.catalog.gui.framework.AbstractGuiTest;
import com.evolveum.midpoint.integration.catalog.gui.pages.ApplicationDetail;
import com.evolveum.midpoint.integration.catalog.gui.pages.ApplicationsList;
import com.evolveum.midpoint.integration.catalog.gui.pages.Header;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

/**
 * Section 2 of both base flow guides: a contributor requests an application, a read-only user may
 * vote for it but not request one, and the requester cancels it, which also removes the test data.
 */
class RequestFlowTest extends AbstractGuiTest {

    private final String applicationName = "e2e_" + System.currentTimeMillis() + "_request";

    @Test
    void contributorRequestsApplicationReadOnlyUserVotesRequesterCancels() {
        Header header = new Header(page);
        ApplicationsList applications = new ApplicationsList(page);

        header.signIn(CONTRIBUTOR);
        applications.openRequestForm().submit(applicationName,
                "Requested by the GUI test.",
                "Provisioning of accounts, to check the request flow.");
        Locator card = applications.findRequestedApplication(applicationName);
        int votes = applications.voteCount(card);
        String applicationUrl = applications.open(card);

        readOnlyUserVotesButCannotRequest();

        ApplicationDetail application = new ApplicationDetail(page);
        application.open(applicationUrl);
        application.expectVoteCount(votes + 1);
        application.cancelRequest();
        header.expectToast("Request cancelled");
    }

    private void readOnlyUserVotesButCannotRequest() {
        try (BrowserContext readOnly = newContext()) {
            Page readOnlyPage = readOnly.newPage();
            new Header(readOnlyPage).signIn(READ_ONLY);
            ApplicationsList applications = new ApplicationsList(readOnlyPage);
            applications.expectRequestDenied();
            applications.vote(applications.findRequestedApplication(applicationName));
        }
    }
}
