/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui.pages;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.evolveum.midpoint.integration.catalog.gui.framework.Ui;

/** The "Edit and upgrade" form. */
public final class MethodEditForm {

    private final Ui ui;

    public MethodEditForm(Ui ui) {
        this.ui = ui;
    }

    public void openFromMethodDetail(String expectedVersion) {
        ui.click("method-detail-edit-and-upgrade");
        expectVersion(expectedVersion);
    }

    /** Also waits until the form is loaded. The version is the full one (e.g. 1.1), unlike the badges. */
    public void expectVersion(String version) {
        assertThat(ui.element("edit-method-version")).hasValue(version);
    }

    public void changeLimitations(String limitations) {
        ui.fill("edit-method-limitations", limitations);
    }

    /** Creates the next minor version. */
    public void save() {
        ui.click("edit-save");
        SubmissionSuccessDialog.confirm(ui);
    }

    /** Creates the next major version. */
    public void saveAsNewVersion() {
        ui.click("edit-save-as-new-version");
        SubmissionSuccessDialog.confirm(ui);
    }
}
