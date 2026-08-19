/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.VerifyBundleInformationForm;
import com.evolveum.midpoint.integration.catalog.exception.ObjectAlreadyExist;
import com.evolveum.midpoint.integration.catalog.form.ContinueForm;
import com.evolveum.midpoint.integration.catalog.form.FailForm;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.evolveum.midpoint.integration.catalog.repository.*;

import com.evolveum.midpoint.integration.catalog.util.RepositoryUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
    private final ConnectorVersionRepository connectorVersionRepository;
    private final ApplicationRepository applicationRepository;
    private final CapabilityRepository capabilityRepository;
    private final ConnVersionCapabilityRepository connVersionCapabilityRepository;

    /**
     * Successful build: activate the integration method and persist capabilities.
     * The OID is the IntegrationMethod UUID.
     */
    @Transactional
    public void successBuild(UUID oid, ContinueForm continueForm) {
        IntegrationMethod method = findIntegrationMethod(oid, continueForm.getIntegrationMethodRevision());
        ConnectorVersion connectorVersion = findConnectorVersion(continueForm.getConnectorVersionId(), continueForm.getConnectorVersionRevision());

        // Resolve connector and bundle version through linked connector
        ConnectorBundle sourceBundle = connectorVersion.getConnector().getConnectorBundle();

        // Handle possible bundle rename / cross-bundle merge
        String newBundleName = continueForm.getConnectorBundle();

        if (newBundleName == null || newBundleName.isBlank()) {
            //todo error
            return;
        }

        Optional<ConnectorBundle> existingBundle = connectorBundleRepository.findByBundleNameAndLifecycleState(newBundleName, LifecycleType.ACTIVE);
        if (existingBundle.isPresent() && sourceBundle != null) {
            ConnectorBundle targetBundle = existingBundle.get();
            sourceBundle.setBundleName(newBundleName);
            sourceBundle.setRevision(RepositoryUtil.uniqueBundleRevision(targetBundle.getBundleName(), targetBundle.getRevision(), connectorBundleRepository));
        } else if (sourceBundle != null) {
            sourceBundle.setBundleName(newBundleName);
        }

        // Update connector version class name
        if (continueForm.getConnectorClass() != null) {
            updateConnectorVersionClassName(connectorVersion.getConnector(), continueForm.getConnectorClass());
        }

        // Persist capabilities on all linked connector versions
        if (continueForm.getCapability() != null && !continueForm.getCapability().isEmpty()) {
            persistCapabilitiesOnConnectorVersions(connectorVersion, continueForm.getCapability());
        }

        connectorVersion.getConnectorBundleVersion().setArtifactUrl(continueForm.getDownloadLink());
        connectorVersion.getConnectorBundleVersion().setBundleVersion(continueForm.getConnectorVersion());

        connectorVersion.setErrorMessage("");
        connectorVersion.getConnectorBundleVersion().setErrorMessage("");

        try {
            Application application = method.getApplication();
            applicationRepository.save(application);
        } catch (Exception e) {
            log.error("Failed save state after success building", e);
        }

        //TODO adding comment to ticket on support portal
    }

    /**
     * Failed build: mark the integration method as errored.
     */
    @Transactional
    public void failBuild(UUID oid, FailForm failForm) {
        IntegrationMethod method = findIntegrationMethod(oid, failForm.getIntegrationMethodRevision());
        ConnectorVersion connectorVersion = findConnectorVersion(failForm.getConnectorVersionId(), failForm.getConnectorVersionRevision());

        String errorMessage = failForm.getErrorMessage();
        connectorVersion.setErrorMessage(errorMessage);
        connectorVersion.getConnectorBundleVersion().setErrorMessage(errorMessage);

        integrationMethodRepository.save(method);
        //TODO adding comment to ticket on support portal
    }

    @Transactional
    public void verify(UUID oid, VerifyBundleInformationForm verifyPayload) {
        ConnectorVersion connectorVersion = findConnectorVersion(verifyPayload.getConnectorVersionId(), verifyPayload.getConnectorVersionRevision());

        String bundleName = verifyPayload.getBundleName();
        String version = verifyPayload.getVersion();
        String className = verifyPayload.getClassName();

        Connector sourceConnector = connectorVersion.getConnector();

        Optional<ConnectorBundle> existingBundle = connectorBundleRepository.findByBundleNameAndLifecycleState(bundleName, LifecycleType.ACTIVE);
        if (existingBundle.isEmpty()) {
            return;
        }

        ConnectorBundle targetBundle = existingBundle.get();

        validateVerifyPayload(version, className);

        if (sourceConnector != null && sourceConnector.getClonedFrom() != null) {
            return;
        }

        Optional<ConnectorBundleVersion> matchingVersion = findMatchingBundleVersion(targetBundle, version);

        if (matchingVersion.isPresent()) {
            checkForClassNameConflict(matchingVersion.get(), className, bundleName, version);
        }
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

    private IntegrationMethod findIntegrationMethod(UUID id, String revision) {
        return integrationMethodRepository.findById(new IntegrationMethodId(id, revision))
                .orElseThrow(() -> new RuntimeException("Integration method not found, UUID: " + id + ", revision: " + revision));
    }

    private ConnectorVersion findConnectorVersion(String id, String revision) {
        return connectorVersionRepository.findById(new ConnectorVersionId(Integer.valueOf(id), revision))
                .orElseThrow(() -> new RuntimeException("Integration method not found, UUID: " + id + ", revision: " + revision));
    }

    private ConnectorBundleVersion resolveConnectorBundleVersion(IntegrationMethod method) {
        if (method.getConnectors() == null || method.getConnectors().isEmpty()) {
            return null;
        }
        IntegrationMethodConnector link = method.getConnectors().get(0);
        if (link.getConnector() == null || link.getConnector().getConnectorVersions().isEmpty()) {
            return null;
        }
        return link.getConnector().getConnectorVersions().get(0).getConnectorBundleVersion();
    }

    private ConnectorBundle resolveConnectorBundle(IntegrationMethod method) {
        if (method.getConnectors() == null || method.getConnectors().isEmpty()) {
            return null;
        }
        IntegrationMethodConnector link = method.getConnectors().get(0);
        if (link.getConnector() == null) {
            return null;
        }
        return link.getConnector().getConnectorBundle();
    }

    private void relinkConnectorToBundle(IntegrationMethod method, ConnectorBundle targetBundle) {
        if (method.getConnectors() == null) {
            return;
        }
        for (IntegrationMethodConnector link : method.getConnectors()) {

            if (link.getConnector() != null) {
                link.getConnector().setConnectorBundle(targetBundle);
            }
        }
    }

    private void updateConnectorVersionClassName(Connector connector, String className) {

        connector.setFullyQualifiedClassName(className);

        for (ConnectorVersion cv : connector.getConnectorVersions()) {
            cv.setFullyQualifiedClassName(className);
        }
    }

    private void persistCapabilitiesOnConnectorVersions(ConnectorVersion connectorVersion,
                                                        List<CapabilityType> capabilityTypes) {
        ConnVersionCapability group = new ConnVersionCapability();
        group.setObjectClass("Global");
        group.setConnectorVersion(connectorVersion);

        for (CapabilityType capType : capabilityTypes) {
            if (!capType.isGlobal()){
                continue;
            }

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

    private Optional<ConnectorBundleVersion> findMatchingBundleVersion(ConnectorBundle bundle, String version) {
        return bundle.getBundleVersions().stream()
                .filter(cbv -> version.equals(cbv.getBundleVersion()))
                .filter(cv -> LifecycleType.ACTIVE == cv.getLifecycleState())
                .findFirst();
    }

    private void validateVerifyPayload(String version, String className) {
        if (version == null || version.isEmpty()) {
            throw new IllegalArgumentException("Request payload lacks connector bundle version.");
        }
        if (className == null || className.isEmpty()) {
            throw new IllegalArgumentException("Request payload lacks connector className.");
        }
    }

    private void checkForClassNameConflict(ConnectorBundleVersion bundleVersion, String className,
                                           String bundleName, String version) {
        boolean conflict = bundleVersion.getConnectorVersions().stream()
                .anyMatch(cv -> className.equals(cv.getFullyQualifiedClassName()));
        if (conflict) {
            throw new ObjectAlreadyExist("Bundle " + bundleName + " version " + version
                    + " already contains connector class " + className);
        }
    }
}
