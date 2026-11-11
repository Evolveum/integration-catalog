/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui.pages;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.evolveum.midpoint.integration.catalog.gui.framework.Ui;
import com.microsoft.playwright.Locator;

/** "My integration methods": one group per method, one row per version. */
public final class MyIntegrationMethods {

    private final Ui ui;

    public MyIntegrationMethods(Ui ui) {
        this.ui = ui;
    }

    /** @param versionBadge the major version as the row shows it, e.g. {@code v1} for both 1.0 and 1.1 */
    public Locator findAwaitingRow(String methodName, String versionBadge) {
        Locator rows = group(methodName).getByTestId("my-methods-row");
        assertThat(rows).hasCount(1);
        assertThat(rows.getByTestId("my-methods-version")).hasText(versionBadge);
        assertThat(rows.getByTestId("my-methods-status-awaiting")).isVisible();
        return rows;
    }

    public void editAndUpgrade(Locator row) {
        row.getByTestId("my-methods-row-actions").click();
        row.getByTestId("my-methods-edit").click();
    }

    public void cancelRequest(Locator row) {
        row.getByTestId("my-methods-row-actions").click();
        row.getByTestId("my-methods-cancel").click();
        ui.click("cancel-request-confirm");
    }

    public void expectNoMethod(String methodName) {
        assertThat(group(methodName)).hasCount(0);
    }

    private Locator group(String methodName) {
        ui.fill("my-methods-search", methodName);
        return ui.element("my-methods-group").filter(new Locator.FilterOptions().setHasText(methodName));
    }
}
