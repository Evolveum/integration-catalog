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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
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
    private final ConnectorBundleVersionRepository connectorBundleVersionRepository;
    private final ConnectorRepository connectorRepository;

    /**
     * Successful build. A build produces one artifact, so the callback is about one connector bundle
     * version and everything on it: the artifact URL and version land on that row, and every connector
     * version built from it gets its class name, its capabilities and a cleared error.
     *
     * <p>The build also reports the Maven bundle name, which is the bundle's real identity. If an active
     * bundle already carries that name, this build belongs to it: the two are merged (see
     * {@link #mergeIntoBundle}) rather than left as two rows claiming the same artifact.
     *
     * <p>The OID is the IntegrationMethod UUID.
     */
    @Transactional
    public void successBuild(UUID oid, ContinueForm continueForm) {
        IntegrationMethod method = findIntegrationMethod(oid, continueForm.getIntegrationMethodRevision());
        ConnectorBundleVersion bundleVersion = resolveBundleVersion(
                continueForm.getConnectorBundleVersionId(), continueForm.getConnectorBundleVersionRevision(),
                continueForm.getConnectorVersionId(), continueForm.getConnectorVersionRevision());

        ConnectorBundle sourceBundle = bundleVersion.getConnectorBundle();
        String newBundleName = continueForm.getConnectorBundle();
        if (newBundleName == null || newBundleName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The build reported no connector bundle name; the bundle's identity cannot be set from it.");
        }

        // The classes this build actually produced, in the order the job listed them.
        List<String> builtClasses = splitClassNames(continueForm.getConnectorClass());
        List<ConnectorVersion> builtVersions = List.copyOf(bundleVersion.getConnectorVersions());

        assignClassNames(builtVersions, builtClasses);

        if (continueForm.getCapability() != null && !continueForm.getCapability().isEmpty()) {
            for (ConnectorVersion cv : builtVersions) {
                persistCapabilitiesOnConnectorVersions(cv, continueForm.getCapability());
            }
        }

        bundleVersion.setArtifactUrl(continueForm.getDownloadLink());
        if (continueForm.getConnectorVersion() != null && !continueForm.getConnectorVersion().isBlank()) {
            bundleVersion.setBundleVersion(continueForm.getConnectorVersion());
        }
        bundleVersion.setErrorMessage("");
        for (ConnectorVersion cv : builtVersions) {
            cv.setErrorMessage("");
        }
        connectorBundleVersionRepository.save(bundleVersion);

        try {
            Application application = method.getApplication();
            applicationRepository.save(application);
        } catch (Exception e) {
            log.error("Failed save state after success building", e);
        }

        // Last, because it re-parents rows with bulk updates and clears the persistence context: nothing
        // loaded above may be touched afterwards.
        adoptBundleName(sourceBundle, bundleVersion, newBundleName);

        //TODO adding comment to ticket on support portal
    }

    /**
     * Failed build: record the error on the bundle version and on every connector version built from it,
     * so the reviewer sees it on each connector rather than only on the one that happened to be named.
     */
    @Transactional
    public void failBuild(UUID oid, FailForm failForm) {
        IntegrationMethod method = findIntegrationMethod(oid, failForm.getIntegrationMethodRevision());
        ConnectorBundleVersion bundleVersion = resolveBundleVersion(
                failForm.getConnectorBundleVersionId(), failForm.getConnectorBundleVersionRevision(),
                failForm.getConnectorVersionId(), failForm.getConnectorVersionRevision());

        String errorMessage = failForm.getErrorMessage();
        bundleVersion.setErrorMessage(errorMessage);
        for (ConnectorVersion cv : bundleVersion.getConnectorVersions()) {
            cv.setErrorMessage(errorMessage);
        }
        connectorBundleVersionRepository.save(bundleVersion);

        integrationMethodRepository.save(method);
        //TODO adding comment to ticket on support portal
    }

    @Transactional
    public void verify(UUID oid, VerifyBundleInformationForm verifyPayload) {
        ConnectorBundleVersion bundleVersion = resolveBundleVersion(
                verifyPayload.getConnectorBundleVersionId(), verifyPayload.getConnectorBundleVersionRevision(),
                verifyPayload.getConnectorVersionId(), verifyPayload.getConnectorVersionRevision());

        String bundleName = verifyPayload.getBundleName();
        String version = verifyPayload.getVersion();
        String className = verifyPayload.getClassName();

        Optional<ConnectorBundle> existingBundle = connectorBundleRepository.findByBundleNameAndLifecycleState(bundleName, LifecycleType.ACTIVE);
        if (existingBundle.isEmpty()) {
            return;
        }

        ConnectorBundle targetBundle = existingBundle.get();

        validateVerifyPayload(version, className);

        // A copy-on-write clone is expected to look like its original, so its class name is not a clash.
        boolean allClones = !bundleVersion.getConnectorVersions().isEmpty()
                && bundleVersion.getConnectorVersions().stream()
                        .map(ConnectorVersion::getConnector)
                        .allMatch(c -> c != null && c.getClonedFrom() != null);
        if (allClones) {
            return;
        }

        Optional<ConnectorBundleVersion> matchingVersion = findMatchingBundleVersion(targetBundle, version);

        if (matchingVersion.isPresent()) {
            checkForClassNameConflict(matchingVersion.get(), className, bundleName, version);
        }
    }

    /**
     * Gives the bundle the name the build reported. When another ACTIVE bundle already answers to that
     * name, the artifact is that bundle's, and this one is merged into it instead of becoming a second
     * row claiming the same name.
     */
    private void adoptBundleName(ConnectorBundle source, ConnectorBundleVersion bundleVersion, String bundleName) {
        if (source == null) {
            return;
        }
        ConnectorBundle target = connectorBundleRepository
                .findByBundleNameAndLifecycleState(bundleName, LifecycleType.ACTIVE)
                .filter(existing -> !existing.getId().equals(source.getId()))
                .orElse(null);
        if (target == null) {
            source.setBundleName(bundleName);
            connectorBundleRepository.save(source);
            return;
        }
        mergeIntoBundle(source, bundleVersion, target);
    }

    /**
     * Merges {@code source} into {@code target}: its connectors become connectors of the target bundle
     * and the built version joins the target's versions, after which the emptied source bundle is
     * deleted. That is what makes a bundle able to hold several connectors — the shape a Maven artifact
     * shipping more than one connector class has always had.
     *
     * <p>If the target already carries this version, the two rows stand for the same build, so the
     * connector versions are moved onto the target's row and the source's is dropped. Downloads recorded
     * against it would go with it, which is safe here only because the row is a freshly built one.
     *
     * <p>Everything is done with bulk updates: both collections involved use orphanRemoval, so moving
     * the entities between them would schedule them for deletion instead. The persistence context is
     * cleared as a result — the caller must not touch loaded entities afterwards.
     */
    private void mergeIntoBundle(ConnectorBundle source, ConnectorBundleVersion bundleVersion, ConnectorBundle target) {
        String version = bundleVersion.getBundleVersion() != null
                ? bundleVersion.getBundleVersion() : bundleVersion.getRevision();
        ConnectorBundleVersion targetVersion = version == null ? null
                : connectorBundleVersionRepository
                        .findByConnectorBundleIdAndBundleVersion(target.getId(), version)
                        .orElse(null);

        if (targetVersion != null) {
            connectorVersionRepository.moveAllToBundleVersion(bundleVersion, targetVersion);
            connectorBundleVersionRepository.deleteRow(bundleVersion.getId(), bundleVersion.getRevision());
        }
        // Any other version this bundle held (a draft that has not been built yet) comes along too.
        connectorBundleVersionRepository.moveAllToBundle(source, target);
        connectorRepository.moveAllToBundle(source, target);
        connectorBundleRepository.deleteRow(source.getId());

        log.info("Build reported bundle name {}: merged bundle {} into {}{}",
                target.getBundleName(), source.getId(), target.getId(),
                targetVersion != null ? " (sharing its existing version " + version + ")" : "");
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

    /**
     * The bundle version a callback is about. Jenkins names it directly; a job that still reports only
     * the connector version it was given is answered through that version's bundle.
     */
    private ConnectorBundleVersion resolveBundleVersion(String bundleVersionId, String bundleVersionRevision,
                                                        String connectorVersionId, String connectorVersionRevision) {
        if (bundleVersionId != null && !bundleVersionId.isBlank()
                && bundleVersionRevision != null && !bundleVersionRevision.isBlank()) {
            return connectorBundleVersionRepository
                    .findById(new ConnectorBundleVersionId(Integer.valueOf(bundleVersionId), bundleVersionRevision))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Connector bundle version not found: " + bundleVersionId + "/" + bundleVersionRevision));
        }
        ConnectorBundleVersion cbv = findConnectorVersion(connectorVersionId, connectorVersionRevision)
                .getConnectorBundleVersion();
        if (cbv == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Connector version " + connectorVersionId + " has no bundle version.");
        }
        return cbv;
    }

    /** The build reports the classes it produced as one comma-separated parameter. */
    private static List<String> splitClassNames(String connectorClass) {
        if (connectorClass == null || connectorClass.isBlank()) {
            return List.of();
        }
        return Arrays.stream(connectorClass.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
    }

    /**
     * Writes the built class names back onto the connector versions of this build.
     *
     * <p>A name the build reports that a version already carries stays where it is — that is the normal
     * case and the only one that is safe to match. Anything left over is handed to the versions that
     * were not matched, in order, which covers the single-connector build whose class name the build
     * corrected. A build reporting fewer classes than there are connectors leaves the rest untouched
     * rather than guessing.
     */
    private void assignClassNames(List<ConnectorVersion> versions, List<String> builtClasses) {
        if (builtClasses.isEmpty()) {
            return;
        }
        List<String> unclaimed = new ArrayList<>(builtClasses);
        List<ConnectorVersion> unmatched = new ArrayList<>();
        for (ConnectorVersion cv : versions) {
            if (cv.getFullyQualifiedClassName() != null && unclaimed.remove(cv.getFullyQualifiedClassName())) {
                continue;
            }
            unmatched.add(cv);
        }
        for (int i = 0; i < unmatched.size() && i < unclaimed.size(); i++) {
            ConnectorVersion cv = unmatched.get(i);
            String className = unclaimed.get(i);
            log.info("Build reported class {} for connector version {}/{} (was {})",
                    className, cv.getId(), cv.getRevision(), cv.getFullyQualifiedClassName());
            cv.setFullyQualifiedClassName(className);
            // connector.fully_qualified_class_name mirrors the newest version.
            Connector connector = cv.getConnector();
            if (connector != null) {
                connector.setFullyQualifiedClassName(className);
                connectorRepository.save(connector);
            }
            connectorVersionRepository.save(cv);
        }
        if (unclaimed.size() > unmatched.size()) {
            log.warn("Build reported {} class(es) that no connector version on this bundle version claims: {}",
                    unclaimed.size() - unmatched.size(), unclaimed.subList(unmatched.size(), unclaimed.size()));
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
