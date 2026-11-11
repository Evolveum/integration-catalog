/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui.framework;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.SelectOption;

/**
 * The only place that knows how to work with a component, so tests and page classes pass just its
 * {@code data-testid} and a value. Ids that repeat (rows, cards, options) use the first match.
 */
public final class Ui {

    private final Page page;

    public Ui(Page page) {
        this.page = page;
    }

    public Locator element(String testId) {
        return page.getByTestId(testId);
    }

    public void click(String testId) {
        element(testId).click();
    }

    public void clickFirst(String testId) {
        element(testId).first().click();
    }

    public void fill(String testId, String value) {
        element(testId).fill(value);
    }

    public void check(String testId) {
        element(testId).check();
    }

    public void checkFirst(String testId) {
        element(testId).first().check();
    }

    /** @param index position in the list, where 0 is usually the "Select..." placeholder */
    public void selectByIndex(String testId, int index) {
        element(testId).selectOption(new SelectOption().setIndex(index));
    }

    /** Opens a dropdown, or a combobox on focus, and picks its first option. */
    public void pickFirstOption(String triggerTestId, String optionTestId) {
        click(triggerTestId);
        clickFirst(optionTestId);
    }

    public void expectVisible(String testId) {
        assertThat(element(testId)).isVisible();
    }

    public void expectHidden(String testId) {
        assertThat(element(testId)).isHidden();
    }
}
