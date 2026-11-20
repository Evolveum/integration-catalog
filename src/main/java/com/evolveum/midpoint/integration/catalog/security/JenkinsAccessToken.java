/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The one shared secret (jenkins.accessToken) Jenkins proves itself with, for both the build
 * callbacks and publishing; each filter decides which endpoints it opens.
 */
final class JenkinsAccessToken {

    static final String HEADER = "X-Jenkins-Token";

    private JenkinsAccessToken() {
    }

    /** Constant-time; false while no secret is configured. */
    static boolean matches(String expected, String provided) {
        if (isBlank(expected) || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
