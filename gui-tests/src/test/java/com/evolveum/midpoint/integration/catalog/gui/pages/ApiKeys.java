/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui.pages;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.evolveum.midpoint.integration.catalog.gui.framework.Ui;
import com.microsoft.playwright.Locator;

/** The "API keys" tab of Settings. Keys are issued by Gravitee, so it must be running. */
public final class ApiKeys {

    private final Ui ui;

    public ApiKeys(Ui ui) {
        this.ui = ui;
    }

    public void open() {
        ui.click("settings-tab-api-keys");
    }

    /** Uses the default expiration. The key itself is shown once, right after it is created. */
    public void create(String name) {
        // Either the button above the list or the one in the empty state, whichever is shown.
        ui.clickFirst("api-keys-create");
        ui.fill("api-key-name", name);
        ui.click("api-key-create-submit");
        ui.expectVisible("api-key-created-secret");
        assertThat(row(name).getByTestId("api-key-status")).hasText("Active");
    }

    /** A revoked key moves to the collapsed list of inactive keys. */
    public void revoke(String name) {
        Locator row = row(name);
        row.getByTestId("api-key-actions").click();
        row.getByTestId("api-key-revoke").click();
        ui.click("api-key-revoke-confirm");
        assertThat(row).hasCount(0);
    }

    private Locator row(String name) {
        return ui.element("api-key-row").filter(new Locator.FilterOptions().setHasText(name));
    }
}
