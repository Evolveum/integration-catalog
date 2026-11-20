/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui.pages;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.evolveum.midpoint.integration.catalog.gui.framework.Ui;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/** The home page, {@code /applications}. */
public final class ApplicationsList {

    private static final Pattern VOTE_COUNT = Pattern.compile("\\((\\d+)\\)");
    private static final Pattern DETAIL_URL = Pattern.compile(".*/applications/[^/?#]+$");

    private final Page page;
    private final Ui ui;

    public ApplicationsList(Page page) {
        this.page = page;
        this.ui = new Ui(page);
    }

    public RequestForm openRequestForm() {
        ui.click("applications-send-request");
        return new RequestForm(ui);
    }

    /** For a user without the right to request, who gets a toast instead of the form. */
    public void expectRequestDenied() {
        ui.click("applications-send-request");
        ui.expectVisible("applications-permission-denied");
    }

    /** Searching also hides the featured row, so the application has just one card. */
    public Locator findRequestedApplication(String name) {
        ui.fill("applications-search", name);
        Locator card = ui.element("applications-card").filter(new Locator.FilterOptions().setHasText(name));
        assertThat(card.getByTestId("applications-card-requested")).isVisible();
        return card;
    }

    /** A request without votes shows no number, so that counts as 0. */
    public int voteCount(Locator card) {
        Matcher count = VOTE_COUNT.matcher(card.getByTestId("applications-card-vote").textContent());
        return count.find() ? Integer.parseInt(count.group(1)) : 0;
    }

    /** Voting twice opens a browser alert, so a test votes once per user. */
    public void vote(Locator card) {
        int before = voteCount(card);
        card.getByTestId("applications-card-vote").click();
        assertThat(card.getByTestId("applications-card-vote")).containsText("(" + (before + 1) + ")");
    }

    /** @return the URL of the application detail page */
    public String open(Locator card) {
        card.click();
        page.waitForURL(DETAIL_URL);
        return page.url();
    }
}
