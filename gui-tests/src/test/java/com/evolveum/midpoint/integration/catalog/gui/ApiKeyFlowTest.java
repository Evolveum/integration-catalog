/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui;

import com.evolveum.midpoint.integration.catalog.gui.framework.AbstractGuiTest;
import com.evolveum.midpoint.integration.catalog.gui.pages.ApiKeys;
import com.evolveum.midpoint.integration.catalog.gui.pages.Header;
import org.junit.jupiter.api.Test;

/** A contributor creates an API key and revokes it again, so no usable key is left behind. */
class ApiKeyFlowTest extends AbstractGuiTest {

    @Test
    void contributorCreatesAndRevokesApiKey() {
        Header header = new Header(page);
        ApiKeys apiKeys = new ApiKeys(ui);
        String keyName = "e2e_" + System.currentTimeMillis() + "_key";

        header.signIn(CONTRIBUTOR);
        header.openSettings();
        apiKeys.open();
        apiKeys.create(keyName);
        apiKeys.revoke(keyName);
    }
}
