/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.VerifyBundleInformationForm;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.evolveum.midpoint.integration.catalog.repository.ConnectorBundleRepository;
import com.evolveum.midpoint.integration.catalog.repository.ConnectorRepository;
import com.evolveum.midpoint.integration.catalog.repository.IntegrationMethodRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

/**
 * Verifies and merges connector bundles after build.
 * Moves Connector and ConnectorBundleVersion entities between ConnectorBundle targets.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BundleMergeService {

    private final ConnectorBundleRepository connectorBundleRepository;
    private final ConnectorRepository connectorRepository;
    private final IntegrationMethodRepository integrationMethodRepository;

    @Transactional
    public boolean verify(VerifyBundleInformationForm verifyPayload) {
        UUID integMethodId = verifyPayload.getOid();
        String bundleName = verifyPayload.getBundleName();
        String version = verifyPayload.getVersion();
        String className = verifyPayload.getClassName();

        IntegrationMethod method = findIntegrationMethod(integMethodId);
        ConnectorBundle sourceBundle = resolveSourceBundle(method);

        ConnectorBundle targetBundle = connectorBundleRepository.findByBundleName(bundleName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No bundle found with bundle name " + bundleName));

        validateVerifyPayload(version, className);

        Optional<ConnectorBundleVersion> matchingVersion = findMatchingBundleVersion(targetBundle, version);

        if (matchingVersion.isPresent()) {
            checkForClassNameConflict(matchingVersion.get(), className, bundleName, version);
            try {
                moveConnectorVersionsAndDeleteBundle(sourceBundle, matchingVersion.get());
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
            }
        } else {
            try {
                moveBundleVersionsAndDeleteBundle(sourceBundle, targetBundle);
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
            }
        }

        return true;
    }

    /**
     * Moves all connectors and bundle versions from source to target bundle, then deletes source.
     */
    @Transactional(rollbackFor = Exception.class)
    public void moveBundleVersionsAndDeleteBundle(ConnectorBundle sourceBundle, ConnectorBundle targetBundle) {
        for (Connector connector : sourceBundle.getConnectors()) {
            connector.setConnectorBundle(targetBundle);
            targetBundle.getConnectors().add(connector);
        }
        sourceBundle.getConnectors().clear();

        for (ConnectorBundleVersion cbv : sourceBundle.getBundleVersions()) {
            cbv.setConnectorBundle(targetBundle);
            targetBundle.getBundleVersions().add(cbv);
        }
        sourceBundle.getBundleVersions().clear();

        connectorBundleRepository.delete(sourceBundle);
        log.debug("Moved all from bundle {} to bundle {}", sourceBundle.getId(), targetBundle.getId());
    }

    /**
     * Moves connector versions from source bundle into a specific target bundle version, then deletes source.
     */
    @Transactional(rollbackFor = Exception.class)
    public void moveConnectorVersionsAndDeleteBundle(ConnectorBundle sourceBundle,
                                                      ConnectorBundleVersion targetBundleVersion) {
        if (sourceBundle.getBundleName() != null && !sourceBundle.getBundleName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Illegal state: source bundle already has a name");
        }

        ConnectorBundle targetBundle = targetBundleVersion.getConnectorBundle();

        for (Connector connector : sourceBundle.getConnectors()) {
            for (ConnectorVersion cv : connector.getConnectorVersions()) {
                cv.setConnectorBundleVersion(targetBundleVersion);
                targetBundleVersion.getConnectorVersions().add(cv);
            }
            connector.setConnectorBundle(targetBundle);
            targetBundle.getConnectors().add(connector);
        }
        sourceBundle.getConnectors().clear();
        sourceBundle.getBundleVersions().clear();

        connectorBundleRepository.delete(sourceBundle);
        log.debug("Moved connector versions to bundle version {}", targetBundleVersion.getId());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private IntegrationMethod findIntegrationMethod(UUID id) {
        return integrationMethodRepository.findByApplicationId(id).stream()
                .filter(m -> m.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Integration method not found: " + id));
    }

    private ConnectorBundle resolveSourceBundle(IntegrationMethod method) {
        if (method.getConnectors() == null || method.getConnectors().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No connector linked to integration method " + method.getId());
        }
        Connector connector = method.getConnectors().get(0).getConnector();
        if (connector == null || connector.getConnectorBundle() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No connector bundle found for integration method " + method.getId());
        }
        return connector.getConnectorBundle();
    }

    private Optional<ConnectorBundleVersion> findMatchingBundleVersion(ConnectorBundle bundle, String version) {
        return bundle.getBundleVersions().stream()
                .filter(cbv -> version.equals(cbv.getBundleVersion()))
                .findFirst();
    }

    private void validateVerifyPayload(String version, String className) {
        if (version == null || version.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Request payload lacks connector bundle version.");
        }
        if (className == null || className.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Request payload lacks connector className.");
        }
    }

    private void checkForClassNameConflict(ConnectorBundleVersion bundleVersion, String className,
                                            String bundleName, String version) {
        boolean conflict = bundleVersion.getConnectorVersions().stream()
                .anyMatch(cv -> className.equals(cv.getFullyQualifiedClassName()));
        if (conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Bundle " + bundleName + " version " + version
                            + " already contains connector class " + className);
        }
    }
}
