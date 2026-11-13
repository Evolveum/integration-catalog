/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui.pages;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.evolveum.midpoint.integration.catalog.gui.framework.SupportPortal;
import com.evolveum.midpoint.integration.catalog.gui.framework.Ui;
import com.microsoft.playwright.Locator;

/** Works with the support portal on or off; see {@link SupportPortal}. */
public final class ApprovalQueue {

    private static final Pattern TICKET_ID = Pattern.compile("#(\\d+)");

    private final Ui ui;
    private final SupportPortal portal;

    public ApprovalQueue(Ui ui, SupportPortal portal) {
        this.ui = ui;
        this.portal = portal;
    }

    /** @param versionBadge the major version as the row shows it, e.g. {@code v1} for both 1.0 and 1.1 */
    public Locator findAwaitingRow(String methodName, String versionBadge) {
        ui.fill("approval-queue-search", methodName);
        Locator row = ui.element("approval-queue-row").filter(new Locator.FilterOptions().setHasText(methodName));
        assertThat(row.getByTestId("approval-queue-status-awaiting")).isVisible();
        assertThat(row.getByTestId("approval-queue-version")).hasText(versionBadge);
        return row;
    }

    public String applicationUrl(Locator row) {
        return row.getByTestId("approval-queue-application-link").getAttribute("href");
    }

    public void startReview(Locator row) {
        row.getByTestId("approval-queue-start-review").click();
        ui.click("start-review-confirm");
        assertThat(row.getByTestId("approval-queue-status-reviewing")).isVisible();
    }

    /** For a version with a new connector: fills its build data by hand, so no Jenkins build is needed. */
    public void approve(Locator row, ManualBuildInfo build) {
        openApproveDialog(row);
        ui.click("approval-manual-fill");
        ui.fill("manual-fill-bundle-name", build.bundleName());
        ui.fill("manual-fill-version", build.version());
        ui.fill("manual-fill-class-name", build.className());
        ui.fill("manual-fill-download-link", build.downloadLink());
        ui.click("manual-fill-submit");
        confirmApproval();
    }

    /** For a version whose connectors all have build data already. */
    public void approve(Locator row) {
        openApproveDialog(row);
        confirmApproval();
    }

    private void openApproveDialog(Locator row) {
        row.getByTestId("approval-queue-actions").click();
        row.getByTestId("approval-queue-approve").click();
    }

    /**
     * The ticket step ends in one of three ways: no ticket (portal off, or not opened yet), a ticket
     * already closed, or an open ticket, which is closed in the portal and then rechecked.
     */
    private void confirmApproval() {
        ui.expectVisible("approval-build-info-complete");
        ui.click("approval-continue");

        // The id reads just "#" while the ticket is loading, so only a number means it is still open.
        Locator openTicket = ui.element("approval-ticket-id").filter(new Locator.FilterOptions().setHasText(TICKET_ID));
        Locator outcome = ui.element("approval-no-ticket").or(ui.element("approval-ticket-ready")).or(openTicket);
        outcome.first().waitFor();
        if (openTicket.isVisible()) {
            Matcher id = TICKET_ID.matcher(openTicket.textContent());
            id.find();
            portal.closeTicket(Integer.parseInt(id.group(1)));
            ui.click("approval-refresh");
            ui.expectVisible("approval-ticket-ready");
        }

        ui.click("approval-confirm");
        ui.expectHidden("approval-dialog");
    }

    public record ManualBuildInfo(String bundleName, String version, String className, String downloadLink) {
    }
}
