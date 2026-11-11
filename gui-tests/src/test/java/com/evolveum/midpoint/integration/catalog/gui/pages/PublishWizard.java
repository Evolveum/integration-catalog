/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui.pages;

import com.evolveum.midpoint.integration.catalog.gui.framework.Ui;

/** The seven steps of "Upload and publish", each method ending on the next step. */
public final class PublishWizard {

    private static final String OBJECT_CLASS = "Account";

    private final Ui ui;

    public PublishWizard(Ui ui) {
        this.ui = ui;
    }

    /** All seven steps with a new application and a new Java connector, starting on the home page. */
    public void publishNewMethod(String applicationName, String methodName,
            String connectorName, String connectorVersion, String connectorClass) {
        open();
        defineNewApplication(applicationName);
        selectFirstMethodType();
        defineMethod(methodName);
        selectJavaConnector();
        defineConnector(connectorName, connectorVersion, connectorClass);
        setCompatibility(connectorVersion);
        submit();
    }

    public void open() {
        ui.click("applications-upload-and-publish");
    }

    /** Confirming the dialog also leaves step 1. Logo upload is switched off (task 13219), so its absence is checked. */
    public void defineNewApplication(String name) {
        ui.click("publish-define-new");
        ui.fill("define-new-name", name);
        ui.expectHidden("define-new-logo-upload");
        ui.click("define-new-continue");
        ui.expectVisible("publish-method-type-title");
    }

    public void selectFirstMethodType() {
        ui.clickFirst("publish-method-type-card");
        ui.click("publish-method-type-continue");
    }

    public void defineMethod(String name) {
        ui.fill("publish-method-name", name);
        ui.pickFirstOption("publish-method-maintainer", "maintainer-option");
        ui.fill("im-capability-object-name", OBJECT_CLASS);
        ui.clickFirst("im-capability-state-yes");
        ui.click("publish-method-continue");
    }

    public void selectJavaConnector() {
        ui.click("publish-connector-type-java");
        ui.click("publish-connector-type-continue");
        ui.expectVisible("publish-connector-title");
    }

    public void defineConnector(String name, String version, String className) {
        ui.fill("publish-connector-name", name);
        ui.fill("publish-connector-version", version);
        ui.pickFirstOption("publish-connector-maintainer", "maintainer-option");
        ui.pickFirstOption("publish-connector-license", "license-option");

        ui.fill("capability-object-class", OBJECT_CLASS);
        ui.click("capability-object-class-trigger");
        ui.checkFirst("capability-object-class-option");
        ui.click("capability-object-class-trigger");

        ui.check("publish-connector-build-maven");
        ui.fill("publish-connector-git-clone-url", "https://github.com/Evolveum/connector-ldap.git");
        ui.fill("publish-connector-commit", "35e32b6");
        ui.fill("publish-connector-class-name", className);
        ui.click("publish-connector-continue");
        ui.expectVisible("publish-compat-title");
    }

    public void setCompatibility(String connectorVersion) {
        ui.selectByIndex("publish-compat-midpoint-from", 1);
        ui.fill("publish-compat-connector-from", connectorVersion);
        ui.click("publish-compat-continue");
        ui.expectVisible("publish-review-title");
    }

    public void submit() {
        ui.check("publish-confirm");
        ui.click("publish-submit");
        SubmissionSuccessDialog.confirm(ui);
    }
}
