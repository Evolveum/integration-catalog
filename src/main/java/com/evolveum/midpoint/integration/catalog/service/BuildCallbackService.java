/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.form.ContinueForm;
import com.evolveum.midpoint.integration.catalog.form.FailForm;
import com.evolveum.midpoint.integration.catalog.object.Application;
import com.evolveum.midpoint.integration.catalog.object.BundleVersion;
import com.evolveum.midpoint.integration.catalog.object.ConnectorBundle;
import com.evolveum.midpoint.integration.catalog.object.Implementation;
import com.evolveum.midpoint.integration.catalog.object.ImplementationVersion;
import com.evolveum.midpoint.integration.catalog.repository.ApplicationRepository;
import com.evolveum.midpoint.integration.catalog.repository.ConnectorBundleRepository;
import com.evolveum.midpoint.integration.catalog.repository.ImplementationVersionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for handling Jenkins build callbacks.
 * Processes success and failure notifications from the build pipeline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuildCallbackService {

    private final ImplementationVersionRepository implementationVersionRepository;
    private final ConnectorBundleRepository connectorBundleRepository;
    private final ApplicationRepository applicationRepository;
    private final BundleMergeService bundleMergeService;

    /**
     * Handles successful build callback from Jenkins pipeline.
     * Updates the implementation version with build information and handles bundle merging if needed.
     *
     * @param oid the implementation version UUID
     * @param continueForm form containing build results (version, download link, capabilities, etc.)
     */
    @Transactional
    public void successBuild(UUID oid, ContinueForm continueForm) {
        ImplementationVersion version = implementationVersionRepository.getReferenceById(oid);
        Implementation implementation = version.getImplementation();

        // Update BundleVersion with build information
        BundleVersion bundleVersion = version.getBundleVersion();
        if (bundleVersion != null) {
            bundleVersion.setConnectorVersion(continueForm.getConnectorVersion());
            bundleVersion.setDownloadLink(continueForm.getDownloadLink());
        }

        // Check if this upload is not just a different version of a similar connector bundle
        String newBundleName = continueForm.getConnectorBundle();
        Optional<ConnectorBundle> existingBundle = connectorBundleRepository.findByBundleName(newBundleName);
        ConnectorBundle sourceBundle = implementation.getConnectorBundle();

        if (existingBundle.isPresent()) {
            // We want to move everything to the target bundle and delete the source
            if (!(sourceBundle.getBundleName() != null && !sourceBundle.getBundleName().isEmpty())) {
                ConnectorBundle targetBundle = existingBundle.get();
                bundleMergeService.moveBundleVersionsAndDeleteBundle(sourceBundle, targetBundle);

                // IMPORTANT: update implementation bundle AFTER the move
                implementation.setConnectorBundle(targetBundle);
            }
        } else {
            // Only update the bundle name if this is not a cross-bundle merge
            if (sourceBundle != null) {
                sourceBundle.setBundleName(newBundleName);
            }
        }

        OffsetDateTime odt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(continueForm.getPublishTime()), ZoneOffset.UTC);
        version.setPublishDate(odt)
                .setLifecycleState(ImplementationVersion.ImplementationVersionLifecycleType.ACTIVE);
        version.setCapabilities(continueForm.getCapability().toArray(new ImplementationVersion.CapabilitiesType[0]));
        version.setClassName(continueForm.getConnectorClass());
        implementationVersionRepository.save(version);

        // Set application lifecycle to ACTIVE on successful build
        try {
            Application application = version.getImplementation().getApplication();
            application.setLifecycleState(Application.ApplicationLifecycleType.ACTIVE);
            applicationRepository.save(application);
        } catch (Exception e) {
            log.error("Failed to update application lifecycle state", e);
        }
    }

    /**
     * Handles failed build callback from Jenkins pipeline.
     * Marks the implementation version as errored and updates application state if needed.
     *
     * @param oid the implementation version UUID
     * @param failForm form containing the error message
     */
    @Transactional
    public void failBuild(UUID oid, FailForm failForm) {
        ImplementationVersion version = implementationVersionRepository.getReferenceById(oid);
        version.setLifecycleState(ImplementationVersion.ImplementationVersionLifecycleType.WITH_ERROR);

        // Set error message on ImplementationVersion
        if (version.getBundleVersion() != null) {
            version.setErrorMessage(failForm.getErrorMessage());
        }

        Implementation implementation = version.getImplementation();
        Application application = implementation.getApplication();

        // If this is the only implementation version of the only implementation,
        // mark the application as errored too
        if (implementation.getImplementationVersions().size() == 1
                && application.getImplementations().size() == 1) {
            application.setLifecycleState(Application.ApplicationLifecycleType.WITH_ERROR);
        }

        implementationVersionRepository.save(version);
    }
}
