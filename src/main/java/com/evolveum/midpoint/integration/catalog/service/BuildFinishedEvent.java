/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import java.util.List;
import java.util.UUID;

/**
 * Raised when a build reports back, so the reviewer reads the outcome on the work package instead
 * of having to open the catalog to find out whether the artifact ever arrived.
 *
 * <p>The build's own results travel on the event rather than being read back from the database: the
 * successful callback merges bundles with bulk updates, after which the rows it loaded say nothing
 * reliable about what was just built.
 *
 * @param bundleName the Maven bundle name the build reported
 * @param bundleVersion the version it produced, when it reported one
 * @param artifactUrl where the artifact can be downloaded
 * @param connectorClasses the connector classes the build produced
 * @param errorMessage why the build failed; {@code null} on a successful one
 */
public record BuildFinishedEvent(
        UUID methodId,
        String revision,
        boolean succeeded,
        String bundleName,
        String bundleVersion,
        String artifactUrl,
        List<String> connectorClasses,
        String errorMessage) {

    public static BuildFinishedEvent succeeded(UUID methodId, String revision, String bundleName,
            String bundleVersion, String artifactUrl, List<String> connectorClasses) {
        return new BuildFinishedEvent(methodId, revision, true, bundleName, bundleVersion,
                artifactUrl, connectorClasses, null);
    }

    public static BuildFinishedEvent failed(UUID methodId, String revision, String errorMessage) {
        return new BuildFinishedEvent(methodId, revision, false, null, null, null, List.of(), errorMessage);
    }
}
