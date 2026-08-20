package com.evolveum.midpoint.integration.catalog.util;

import com.evolveum.midpoint.integration.catalog.object.ConnectorVersion;
import com.evolveum.midpoint.integration.catalog.object.ConnectorVersionId;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethod;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodId;
import com.evolveum.midpoint.integration.catalog.repository.ConnectorBundleRepository;
import com.evolveum.midpoint.integration.catalog.repository.ConnectorVersionRepository;
import com.evolveum.midpoint.integration.catalog.repository.IntegrationMethodRepository;

import java.util.UUID;

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

    public static IntegrationMethod findIntegrationMethod(
            UUID id, String revision, IntegrationMethodRepository integrationMethodRepository) {
        return integrationMethodRepository.findById(new IntegrationMethodId(id, revision))
                .orElseThrow(() -> new RuntimeException("Integration method not found, UUID: " + id + ", revision: " + revision));
    }

    public static ConnectorVersion findConnectorVersion(
            String id, String revision, ConnectorVersionRepository connectorVersionRepository) {
        return connectorVersionRepository.findById(new ConnectorVersionId(Integer.valueOf(id), revision))
                .orElseThrow(() -> new RuntimeException("Integration method not found, UUID: " + id + ", revision: " + revision));
    }
}
