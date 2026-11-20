/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.security;

import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The connector Jenkins pipeline, signed in by {@link JenkinsPublishFilter}. It has no account of
 * its own, so it names the person it publishes for; that person is only recorded as the author and
 * grants nothing - the name the services authorize is always {@link #NAME}.
 */
public record JenkinsPublisher(String authorUsername, String authorEmail) implements AuthenticatedPrincipal {

    public static final String NAME = "jenkins";

    @Override
    public String getName() {
        return NAME;
    }

    /** The pipeline when it is the caller of the current request, otherwise null. */
    public static JenkinsPublisher current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof JenkinsPublisher publisher
                ? publisher
                : null;
    }
}
