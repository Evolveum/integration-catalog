/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui.pages;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.evolveum.midpoint.integration.catalog.gui.framework.Ui;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/** Version rows of the application's integration methods; the first method is expanded on load. */
public final class ApplicationDetail {

    private final Page page;
    private final Ui ui;

    public ApplicationDetail(Page page) {
        this.page = page;
        this.ui = new Ui(page);
    }

    public void open(String applicationUrl) {
        page.navigate(applicationUrl);
    }

    /** @param versionBadge the major version as the row shows it, e.g. {@code v1} for both 1.0 and 1.1 */
    public void openPublishedVersionDetails(String versionBadge) {
        Locator row = publishedVersion(versionBadge);
        // The row actions are displayed only while the row is hovered.
        row.hover();
        row.getByTestId("application-version-view-details").click();
    }

    public void expectPublishedVersion(String versionBadge) {
        assertThat(publishedVersion(versionBadge)).isVisible();
    }

    /** For a requested application, which shows votes instead of downloads. */
    public void expectVoteCount(int votes) {
        assertThat(ui.element("application-vote-count")).hasText(votes + " vote(s)");
    }

    /** Withdraws a requested application; the page then returns to the home page. */
    public void cancelRequest() {
        ui.click("application-cancel-request");
        ui.click("cancel-request-confirm");
    }

    private Locator publishedVersion(String versionBadge) {
        Locator badge = page.getByTestId("application-version-badge")
                .filter(new Locator.FilterOptions().setHasText(versionBadge));
        return ui.element("application-version-row")
                .filter(new Locator.FilterOptions().setHas(page.getByTestId("application-version-status-published")))
                .filter(new Locator.FilterOptions().setHas(badge));
    }
}
