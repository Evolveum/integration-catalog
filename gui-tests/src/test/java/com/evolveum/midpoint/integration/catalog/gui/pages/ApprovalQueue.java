/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui.pages;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.evolveum.midpoint.integration.catalog.gui.framework.Ui;
import com.microsoft.playwright.Locator;

/** Approval needs a backend with the support portal off, so the dialog shows "No support ticket". */
public final class ApprovalQueue {

    private final Ui ui;

    public ApprovalQueue(Ui ui) {
        this.ui = ui;
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

    private void confirmApproval() {
        ui.expectVisible("approval-build-info-complete");
        ui.click("approval-continue");
        ui.expectVisible("approval-no-ticket");
        ui.click("approval-confirm");
        ui.expectHidden("approval-dialog");
    }

    public record ManualBuildInfo(String bundleName, String version, String className, String downloadLink) {
    }
}
