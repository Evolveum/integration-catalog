/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui.pages;

import com.evolveum.midpoint.integration.catalog.gui.framework.Ui;

/** The "Send a request" dialog; the optional fields are left empty. */
public final class RequestForm {

    private static final String OBJECT_CLASS = "Account";

    private final Ui ui;

    RequestForm(Ui ui) {
        this.ui = ui;
    }

    public void submit(String applicationName, String description, String integrationNeed) {
        ui.fill("request-app-name", applicationName);
        ui.fill("request-description", description);
        ui.fill("request-integration-need", integrationNeed);
        ui.fill("im-capability-object-name", OBJECT_CLASS);
        ui.clickFirst("im-capability-state-yes");
        ui.click("request-submit");
        ui.expectVisible("request-success-title");
    }
}
