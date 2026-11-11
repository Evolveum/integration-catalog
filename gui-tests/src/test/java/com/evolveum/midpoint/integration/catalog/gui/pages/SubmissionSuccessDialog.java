/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui.pages;

import com.evolveum.midpoint.integration.catalog.gui.framework.Ui;

/** Shown after publishing, Save and Save as new version alike. */
public final class SubmissionSuccessDialog {

    private SubmissionSuccessDialog() {
    }

    public static void confirm(Ui ui) {
        ui.expectVisible("submission-success-title");
        ui.click("submission-success-done");
    }
}
