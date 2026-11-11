/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui.pages;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.evolveum.midpoint.integration.catalog.gui.framework.Account;
import com.evolveum.midpoint.integration.catalog.gui.framework.Ui;
import com.microsoft.playwright.Page;

/** The header is on every page, so it also holds the toast that pages show through it. */
public final class Header {

    private final Page page;
    private final Ui ui;

    public Header(Page page) {
        this.page = page;
        this.ui = new Ui(page);
    }

    public void signIn(Account account) {
        page.navigate("/applications");
        ui.click("header-login");

        // Keycloak's own page, not one of our templates, so it has no test ids.
        page.waitForURL(url -> url.contains("/realms/"));
        page.locator("#username").fill(account.username());
        page.locator("#password").fill(account.password());
        page.locator("#kc-login").click();
        page.waitForURL(url -> !url.contains("/realms/"));

        // Spring can send the browser back to an API URL it remembered from before the login.
        page.navigate("/applications");
        ui.expectVisible("header-user-menu");
    }

    public void openApprovalQueue() {
        ui.click("header-user-menu");
        ui.click("header-menu-approval-queue");
    }

    /** Not in a superuser's menu, which has "All integration methods" instead. */
    public void openMyIntegrationMethods() {
        ui.click("header-user-menu");
        ui.click("header-menu-my-methods");
    }

    public void expectToast(String title) {
        assertThat(ui.element("toast-title")).hasText(title);
    }
}
