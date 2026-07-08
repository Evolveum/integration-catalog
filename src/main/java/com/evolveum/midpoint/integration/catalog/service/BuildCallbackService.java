/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.form.ContinueForm;
import com.evolveum.midpoint.integration.catalog.form.FailForm;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.evolveum.midpoint.integration.catalog.repository.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles Jenkins build callbacks — updates IntegrationMethod lifecycle and capabilities.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuildCallbackService {

    private final IntegrationMethodRepository integrationMethodRepository;
    private final ConnectorBundleRepository connectorBundleRepository;
    private final ApplicationRepository applicationRepository;
    private final CapabilityRepository capabilityRepository;
    private final ConnVersionCapabilityRepository connVersionCapabilityRepository;
    private final BundleMergeService bundleMergeService;

    /**
     * Successful build: activate the integration method and persist capabilities.
     * The OID is the IntegrationMethod UUID.
     */
    @Transactional
    public void successBuild(UUID oid, ContinueForm continueForm) {
        IntegrationMethod method = findLatestRevision(oid);

        // Resolve connector and bundle version through linked connector
        ConnectorBundleVersion bundleVersion = resolveConnectorBundleVersion(method);
        ConnectorBundle sourceBundle = resolveConnectorBundle(method);

        // Handle possible bundle rename / cross-bundle merge
        String newBundleName = continueForm.getConnectorBundle();
        if (newBundleName != null && !newBundleName.isBlank()) {
            Optional<ConnectorBundle> existingBundle = connectorBundleRepository.findByBundleName(newBundleName);
            if (existingBundle.isPresent() && sourceBundle != null) {
                ConnectorBundle targetBundle = existingBundle.get();
                if (sourceBundle.getBundleName() == null || sourceBundle.getBundleName().isBlank()) {
                    bundleMergeService.moveBundleVersionsAndDeleteBundle(sourceBundle, targetBundle);
                    relinkConnectorToBundle(method, targetBundle);
                }
            } else if (sourceBundle != null) {
                sourceBundle.setBundleName(newBundleName);
            }
        }

        // Update connector version class name
        if (continueForm.getConnectorClass() != null) {
            updateConnectorVersionClassName(method, continueForm.getConnectorClass());
        }

        // Persist capabilities on all linked connector versions
        if (continueForm.getCapability() != null && !continueForm.getCapability().isEmpty()) {
            persistCapabilitiesOnConnectorVersions(method, continueForm.getCapability());
        }

        // Activate integration method
        method.setLifecycleState(LifecycleType.ACTIVE);

        // Activate application
        try {
            Application application = method.getApplication();
            application.setLifecycleState(Application.ApplicationLifecycleType.ACTIVE);
            applicationRepository.save(application);
        } catch (Exception e) {
            log.error("Failed to update application lifecycle state", e);
        }
    }

    /**
     * Failed build: mark the integration method as errored.
     */
    @Transactional
    public void failBuild(UUID oid, FailForm failForm) {
        IntegrationMethod method = findLatestRevision(oid);
        method.setLifecycleState(LifecycleType.WITH_ERROR);

        Application application = method.getApplication();
        List<IntegrationMethod> allMethods = integrationMethodRepository.findByApplicationId(application.getId());
        if (allMethods.size() == 1) {
            application.setLifecycleState(Application.ApplicationLifecycleType.WITH_ERROR);
            applicationRepository.save(application);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private IntegrationMethod findLatestRevision(UUID id) {
        return integrationMethodRepository.findByApplicationId(id).stream()
                .filter(m -> m.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Integration method not found: " + id));
    }

    private ConnectorBundleVersion resolveConnectorBundleVersion(IntegrationMethod method) {
        if (method.getConnectors() == null || method.getConnectors().isEmpty()) return null;
        IntegrationMethodConnector link = method.getConnectors().get(0);
        if (link.getConnector() == null || link.getConnector().getConnectorVersions().isEmpty()) return null;
        return link.getConnector().getConnectorVersions().get(0).getConnectorBundleVersion();
    }

    private ConnectorBundle resolveConnectorBundle(IntegrationMethod method) {
        if (method.getConnectors() == null || method.getConnectors().isEmpty()) return null;
        IntegrationMethodConnector link = method.getConnectors().get(0);
        if (link.getConnector() == null) return null;
        return link.getConnector().getConnectorBundle();
    }

    private void relinkConnectorToBundle(IntegrationMethod method, ConnectorBundle targetBundle) {
        if (method.getConnectors() == null) return;
        for (IntegrationMethodConnector link : method.getConnectors()) {
            if (link.getConnector() != null) {
                link.getConnector().setConnectorBundle(targetBundle);
            }
        }
    }

    private void updateConnectorVersionClassName(IntegrationMethod method, String className) {
        if (method.getConnectors() == null) return;
        for (IntegrationMethodConnector link : method.getConnectors()) {
            if (link.getConnector() == null) continue;
            for (ConnectorVersion cv : link.getConnector().getConnectorVersions()) {
                cv.setFullyQualifiedClassName(className);
            }
        }
    }

    private void persistCapabilitiesOnConnectorVersions(IntegrationMethod method,
                                                         List<CapabilityType> capabilityTypes) {
        if (method.getConnectors() == null) return;
        for (IntegrationMethodConnector link : method.getConnectors()) {
            if (link.getConnector() == null) continue;
            for (ConnectorVersion cv : link.getConnector().getConnectorVersions()) {
                ConnVersionCapability group = new ConnVersionCapability();
                group.setObjectClass("__ACCOUNT__");
                group.setConnectorVersion(cv);

                for (CapabilityType capType : capabilityTypes) {
                    Capability cap = capabilityRepository.findByName(capType.name())
                            .orElseGet(() -> {
                                Capability c = new Capability();
                                c.setName(capType.name());
                                return capabilityRepository.save(c);
                            });

                    ConnVersionCapabilityItem item = new ConnVersionCapabilityItem();
                    item.setConnVersionCapabilityId(group.getId());
                    item.setCapabilityId(cap.getId());
                    item.setConnVersionCapability(group);
                    item.setCapability(cap);
                    group.getItems().add(item);
                }
                connVersionCapabilityRepository.save(group);
            }
        }
    }
}
