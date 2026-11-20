/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui.pages;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.evolveum.midpoint.integration.catalog.gui.framework.Ui;
import com.microsoft.playwright.Locator;

/** "All connectors" (superuser) or "My connectors": one group per connector, one row per version. */
public final class Connectors {

    private final Ui ui;

    public Connectors(Ui ui) {
        this.ui = ui;
    }

    /**
     * Checks that publishing, editing and reusing left exactly one connector of that name, with each
     * version listed once.
     */
    public void expectSingleConnector(String name, String... versions) {
        ui.fill("connectors-search", name);
        Locator groups = ui.element("connectors-group").filter(new Locator.FilterOptions().setHasText(name));
        assertThat(groups).hasCount(1);
        Locator chips = groups.getByTestId("connectors-version");
        assertThat(chips).hasCount(versions.length);
        for (String version : versions) {
            assertThat(chips.filter(new Locator.FilterOptions().setHasText(version))).hasCount(1);
        }
    }

    /** E.g. {@code "Used by 2 integration methods"}. */
    public void expectUsage(String name, String usage) {
        ui.fill("connectors-search", name);
        Locator group = ui.element("connectors-group").filter(new Locator.FilterOptions().setHasText(name));
        assertThat(group.getByTestId("connectors-group-summary")).containsText(usage);
    }
}
