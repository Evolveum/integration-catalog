/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui.framework;

/** A Keycloak user the tests sign in as. */
public record Account(String username, String password) {

    /** Reads {@code gui.<key>.username} and {@code gui.<key>.password}; the password defaults to the username. */
    static Account fromProperties(String key, String defaultUsername) {
        String username = System.getProperty("gui." + key + ".username", defaultUsername);
        return new Account(username, System.getProperty("gui." + key + ".password", username));
    }
}
