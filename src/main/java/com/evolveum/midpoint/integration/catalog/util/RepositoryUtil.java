package com.evolveum.midpoint.integration.catalog.util;

import com.evolveum.midpoint.integration.catalog.repository.ConnectorBundleRepository;

public class RepositoryUtil {

    /**
     * Picks a bundle revision that keeps (bundle_name, revision) unique for a freshly cloned bundle.
     */
    public static String uniqueBundleRevision(String bundleName, String baseRevision, ConnectorBundleRepository connectorBundleRepository) {
        String base = baseRevision != null ? baseRevision : "1.0.0";
        if (bundleName == null) {
            return base;
        }
        String candidate = base;
        int suffix = 1;
        while (connectorBundleRepository.existsByBundleNameAndRevision(bundleName, candidate)) {
            candidate = base + "-" + suffix;
            suffix++;
        }
        return candidate;
    }
}
