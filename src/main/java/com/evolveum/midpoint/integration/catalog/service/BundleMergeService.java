/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.VerifyBundleInformationForm;
import com.evolveum.midpoint.integration.catalog.object.BundleVersion;
import com.evolveum.midpoint.integration.catalog.object.ConnectorBundle;
import com.evolveum.midpoint.integration.catalog.object.Implementation;
import com.evolveum.midpoint.integration.catalog.object.ImplementationVersion;
import com.evolveum.midpoint.integration.catalog.repository.ConnectorBundleRepository;
import com.evolveum.midpoint.integration.catalog.repository.ImplementationVersionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

/**
 * Service for verifying and merging connector bundles.
 * Handles the logic for moving implementations and bundle versions between connector bundles.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BundleMergeService {

    private final ConnectorBundleRepository connectorBundleRepository;
    private final ImplementationVersionRepository implementationVersionRepository;

    // ==================== Public API ====================

    /**
     * Verify validity of the implementation version based on the data produced by the Jenkins pipeline.
     * The process checks if a new connector bundle implementation version should be assigned to an existing connector-bundle.
     * Used in case of bundled connectors, i.e. LDAP contains an implementation (connector class) for LDAP connectors
     * but also an implementation version which handles AD based systems.
     *
     * @param verifyPayload JSON form used to verify the validity of the implementation version
     * @return true if the implementation version is valid and the Jenkins pipeline can proceed
     */
    @Transactional
    public boolean verify(VerifyBundleInformationForm verifyPayload) {
        UUID implementationVersionId = verifyPayload.getOid();
        String bundleName = verifyPayload.getBundleName();
        String version = verifyPayload.getVersion();
        String className = verifyPayload.getClassName();

        // 1. Find source bundle and implementation version
        ConnectorBundle sourceBundle = findSourceBundle(implementationVersionId);
        ImplementationVersion sourceImplVersion = findImplementationVersionOrThrow(implementationVersionId);

        // 2. Find target bundle by name
        ConnectorBundle targetBundle;
        try {
            targetBundle = findTargetBundle(bundleName);
        } catch (ResponseStatusException e) {
            sourceImplVersion.setErrorMessage(e.getReason());
            throw e;
        }

        // 3. Validate payload
        validateVerifyPayload(version, className, sourceImplVersion);

        // 4. Check if target bundle has a matching version
        Optional<BundleVersion> matchingBundleVersion = findMatchingBundleVersion(targetBundle, version);

        if (matchingBundleVersion.isPresent()) {
            // 5a. Version exists - check for conflict and move implementation version
            BundleVersion targetBundleVersion = matchingBundleVersion.get();
            checkForClassNameConflict(targetBundleVersion, className, bundleName, version, sourceImplVersion);

            try {
                moveImplVersionAndDeleteBundle(sourceBundle, targetBundleVersion, sourceImplVersion);
            } catch (Exception e) {
                handleVerifyError(sourceImplVersion, e);
            }
        } else {
            // 5b. Version doesn't exist - move all bundle versions to target
            try {
                moveBundleVersionsAndDeleteBundle(sourceBundle, targetBundle);
            } catch (Exception e) {
                handleVerifyError(sourceImplVersion, e);
            }
        }

        return true;
    }

    /**
     * Moves all bundle versions and implementations from source to target bundle, then deletes source.
     * Used when merging bundles during successful build or verification.
     *
     * @param sourceBundle the bundle to move from (will be deleted)
     * @param targetBundle the bundle to move to
     */
    @Transactional(rollbackFor = Exception.class)
    public void moveBundleVersionsAndDeleteBundle(ConnectorBundle sourceBundle, ConnectorBundle targetBundle) {
        // Move implementations
        for (Implementation impl : sourceBundle.getImplementations()) {
            impl.setConnectorBundle(targetBundle);
            targetBundle.getImplementations().add(impl);
        }
        sourceBundle.getImplementations().clear();

        // Move bundle versions
        for (BundleVersion bv : sourceBundle.getBundleVersions()) {
            bv.setConnectorBundle(targetBundle);
            targetBundle.getBundleVersions().add(bv);
        }
        sourceBundle.getBundleVersions().clear();

        connectorBundleRepository.delete(sourceBundle);
        log.debug("Moved all versions from bundle {} to bundle {}",
                sourceBundle.getId(), targetBundle.getId());
    }

    /**
     * Moves a single implementation version to a target bundle version, then deletes the source bundle.
     * Used when the target bundle already has a matching version.
     *
     * @param sourceBundle the source bundle (will be deleted)
     * @param targetBundleVersion the target bundle version to move the implementation to
     * @param sourceImplVersion the implementation version to move
     */
    @Transactional(rollbackFor = Exception.class)
    public void moveImplVersionAndDeleteBundle(ConnectorBundle sourceBundle,
                                                BundleVersion targetBundleVersion,
                                                ImplementationVersion sourceImplVersion) {
        // Validate source bundle state
        if (sourceBundle.getBundleName() != null && !sourceBundle.getBundleName().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Illegal state of connector bundle. Bundle already contains implementation version which is being verified");
        }

        // Move the implementation version to target
        sourceImplVersion.setBundleVersion(targetBundleVersion);
        targetBundleVersion.getImplementationVersions().add(sourceImplVersion);

        ConnectorBundle targetBundle = targetBundleVersion.getConnectorBundle();

        // Clean up source bundle versions and delete other implementation versions
        for (BundleVersion bv : sourceBundle.getBundleVersions()) {
            for (ImplementationVersion iv : bv.getImplementationVersions()) {
                if (iv.getId().equals(sourceImplVersion.getId())) {
                    continue;
                }
                implementationVersionRepository.delete(iv);
            }
            bv.getImplementationVersions().clear();
            bv.setConnectorBundle(null);
        }
        sourceBundle.getBundleVersions().clear();

        // Move implementations to target
        for (Implementation impl : sourceBundle.getImplementations()) {
            impl.setConnectorBundle(targetBundle);
            targetBundle.getImplementations().add(impl);
        }
        sourceBundle.getImplementations().clear();

        connectorBundleRepository.delete(sourceBundle);
        log.debug("Moved implementation version {} to bundle version {}",
                sourceImplVersion.getId(), targetBundleVersion.getId());
    }

    // ==================== Lookup Methods ====================

    /**
     * Finds the source connector bundle containing the given implementation version.
     */
    private ConnectorBundle findSourceBundle(UUID implementationVersionId) {
        return connectorBundleRepository
                .findByBundleVersions_ImplementationVersions_Id(implementationVersionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No bundle found containing the connector implementation version with OID " + implementationVersionId));
    }

    /**
     * Finds a connector bundle by its bundle name.
     */
    private ConnectorBundle findTargetBundle(String bundleName) {
        return connectorBundleRepository
                .findByBundleName(bundleName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No bundle found with bundle name " + bundleName));
    }

    /**
     * Finds an implementation version by ID.
     */
    private ImplementationVersion findImplementationVersionOrThrow(UUID implementationVersionId) {
        return implementationVersionRepository
                .findById(implementationVersionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No implementation version found with id " + implementationVersionId));
    }

    /**
     * Finds a bundle version within a connector bundle that matches the given version string.
     */
    private Optional<BundleVersion> findMatchingBundleVersion(ConnectorBundle bundle, String version) {
        return bundle.getBundleVersions().stream()
                .filter(bv -> version.equals(bv.getConnectorVersion()))
                .findFirst();
    }

    // ==================== Validation Methods ====================

    /**
     * Validates that the verify payload contains required fields.
     */
    private void validateVerifyPayload(String version, String className, ImplementationVersion implVersion) {
        if (version == null || version.isEmpty()) {
            String err = "Request payload lacks connector bundle version.";
            implVersion.setErrorMessage(err);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, err);
        }

        if (className == null || className.isEmpty()) {
            String err = "Request payload lacks connector className.";
            implVersion.setErrorMessage(err);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, err);
        }
    }

    /**
     * Checks if a bundle version already contains an implementation with the given class name.
     */
    private void checkForClassNameConflict(BundleVersion bundleVersion, String className,
                                           String bundleName, String version, ImplementationVersion implVersion) {
        boolean hasConflict = bundleVersion.getImplementationVersions().stream()
                .anyMatch(iv -> className.equals(iv.getClassName()));

        if (hasConflict) {
            String err = "The connector bundle " + bundleName + " with the version " + version
                    + " already contains an implementation for the connector class " + className;
            implVersion.setErrorMessage(err);
            throw new ResponseStatusException(HttpStatus.CONFLICT, err);
        }
    }

    // ==================== Error Handling ====================

    /**
     * Sets error message on implementation version and throws ResponseStatusException.
     */
    private void handleVerifyError(ImplementationVersion implVersion, Exception e) {
        String err = e.getLocalizedMessage();
        implVersion.setErrorMessage(err);
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, err);
    }
}
