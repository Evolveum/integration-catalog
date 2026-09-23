/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.common.ItemFile;
import com.evolveum.midpoint.integration.catalog.configuration.GithubProperties;
import com.evolveum.midpoint.integration.catalog.configuration.JenkinsProperties;
import com.evolveum.midpoint.integration.catalog.dto.AddConnectorDto;
import com.evolveum.midpoint.integration.catalog.dto.ApplicationTagDto;
import com.evolveum.midpoint.integration.catalog.dto.EditConnectorDto;
import com.evolveum.midpoint.integration.catalog.dto.EditIntegrationMethodDto;
import com.evolveum.midpoint.integration.catalog.dto.IntegrationMethodCapabilityGroupDto;
import com.evolveum.midpoint.integration.catalog.dto.UploadConnectorDto;
import com.evolveum.midpoint.integration.catalog.dto.UploadIntegrationDto;
import com.evolveum.midpoint.integration.catalog.dto.UploadIntegrationMethodDto;
import com.evolveum.midpoint.integration.catalog.integration.GithubClient;
import com.evolveum.midpoint.integration.catalog.integration.JenkinsClient;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.evolveum.midpoint.integration.catalog.repository.*;
import com.evolveum.midpoint.integration.catalog.object.ConnVersionCapability;
import com.evolveum.midpoint.integration.catalog.object.ConnVersionCapabilityItem;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodType;

import com.evolveum.midpoint.integration.catalog.service.event.ConnectorAddedToReviewEvent;
import com.evolveum.midpoint.integration.catalog.service.event.IntegrationMethodSubmittedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.HttpException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorUploadService {

    public static final String DEFAULT_REVISION = "1.0";
    /**
     * The reserved object class holding resource-wide capabilities. Connectors keep it; integration
     * methods do not declare it any more, so it is dropped from everything they save.
     */
    private static final String GLOBAL_OBJECT_CLASS = "Global";
    private final ApplicationRepository applicationRepository;
    private final IntegrationMethodRepository integrationMethodRepository;
    private final ConnectorRepository connectorRepository;
    private final ConnectorVersionRepository connectorVersionRepository;
    private final ConnectorBundleRepository connectorBundleRepository;
    private final ConnectorBundleVersionRepository connectorBundleVersionRepository;
    private final DownloadRepository downloadRepository;
    private final GithubProperties githubProperties;
    private final JenkinsProperties jenkinsProperties;
    private final ApplicationTagService applicationTagService;
    private final CapabilityRepository capabilityRepository;
    private final IntegrationMethodCapabilityRepository integrationMethodCapabilityRepository;
    private final IntegrationMethodCapabilityItemRepository integrationMethodCapabilityItemRepository;
    private final ConnVersionCapabilityRepository connVersionCapabilityRepository;
    private final ConnVersionCapabilityItemRepository connVersionCapabilityItemRepository;
    private final IntegrationMethodTypeRepository integrationMethodTypeRepository;
    private final IntegrationMethodConnectorRepository integrationMethodConnectorRepository;
    private final TutorialStorageService tutorialStorageService;
    private final OwnershipService ownershipService;
    private final ApplicationEventPublisher events;

    private record ApplicationResolution(
            Application application,
            boolean isNew,
            List<String> originNames,
            List<ApplicationTagDto> tagDtos) {
    }

    /**
     * @param isNewConnector whether {@link #connector()} is a connector already published in the
     *                       catalog, linked as it is. Nothing about it is created or changed, so the
     *                       publish skips everything that exists to get a new connector built and
     *                       reviewed.
     */
    private record UploadResolution(
            IntegrationMethod integrationMethod,
            Connector connector,
            ConnectorBundle bundle,
            boolean isNewConnector) {
    }

    @Transactional
    public String uploadIntegration(UploadIntegrationDto dto, String username) {
        ApplicationResolution appRes = resolveApplication(dto);
        UploadResolution uploadRes = resolveUpload(dto, appRes.application(), username);

        ownershipService.stampNew(uploadRes.integrationMethod(), username, dto.connector().maintainer());

        applicationTagService.processOrigins(appRes.application(), appRes.originNames(), appRes.isNew());
        applicationTagService.processTags(appRes.application(), appRes.tagDtos(), appRes.isNew());

        if (uploadRes.isNewConnector()) {
            ConnectorBundleVersion bundleVersion = createBundleVersion(
                    dto.connector(), uploadRes.bundle(), username);
            bundleVersion.setConnectorBundle(uploadRes.bundle());

            ConnectorVersion connectorVersion = createConnectorVersion(
                    dto.connector(), uploadRes.connector(), bundleVersion, username);
            connectorVersion.setConnector(uploadRes.connector());

            uploadRes.bundle().getConnectors().add(uploadRes.connector());

            //don't support for now
//            createGitHubRepositoryIfNeeded(uploadRes, bundleVersion, connectorVersion, dto.files());

            persistEntities(uploadRes, bundleVersion, connectorVersion);
            saveConnectorVersionCapabilities(dto, connectorVersion);
        }

        IntegrationMethodConnector imc = new IntegrationMethodConnector();
        imc.setConnector(uploadRes.connector());
        imc.setConnectorMinVersion(dto.connector().connectorMinVersion());
        imc.setConnectorMaxVersion(dto.connector().connectorMaxVersion());
        imc.setIntegrationMethod(uploadRes.integrationMethod());
        uploadRes.integrationMethod().getConnectors().add(imc);

        persistEntities(appRes, uploadRes);
        saveIntegrationMethodCapabilities(dto, uploadRes.integrationMethod());

        // The revision is now in front of a reviewer; open its support work package once this commits.
        events.publishEvent(new IntegrationMethodSubmittedEvent(
                uploadRes.integrationMethod().getId(), uploadRes.integrationMethod().getRevision(),
                SubmissionFlow.CREATE, null));

        return appRes.application().getId() + "|" + uploadRes.integrationMethod().getId();
    }

    private ApplicationResolution resolveApplication(UploadIntegrationDto dto) {
        Application application = dto.application();
        boolean isNew = (application.getId() == null);
        List<String> originNames = application.getOrigins();
        List<ApplicationTagDto> tagDtos = application.getTags();

        if (application.getId() != null) {
            application = applicationRepository.findById(application.getId())
                    .orElseThrow(() -> new RuntimeException("Application not found"));
        } else {
            if (application.getName() == null || application.getName().isEmpty()) {
                String generatedName = application.getDisplayName()
                        .toLowerCase()
                        .replaceAll("\\s+", "_")
                        .replaceAll("[^a-z0-9_]", "")
                        .replaceAll("_+", "_")
                        .replaceAll("^_|_$", "");
                application.setName(generatedName);
            }

            application.setLifecycleState(Application.ApplicationLifecycleType.IN_REVIEW);
        }
        return new ApplicationResolution(application, isNew, originNames, tagDtos);
    }

    private UploadResolution resolveUpload(UploadIntegrationDto dto, Application application, String username) {
        UploadIntegrationMethodDto imDto = dto.integrationMethod();
        UploadConnectorDto connDto = dto.connector();
        boolean isNewConnector = true;

        IntegrationMethod integrationMethod;
        Connector connector;
        ConnectorBundle bundle;

        integrationMethod = new IntegrationMethod();
        integrationMethod.setApplication(application);
        integrationMethod.setLifecycleState(LifecycleType.IN_REVIEW);
        integrationMethod.setMidpointMinVersionId(imDto.midpointMinVersion());
        integrationMethod.setMidpointMaxVersionId(imDto.midpointMaxVersion());

        if (imDto.displayName() != null) {
            integrationMethod.setDisplayName(imDto.displayName());
        }
        if (imDto.revision() != null) {
            integrationMethod.setRevision(imDto.revision());
        }
        if (imDto.description() != null) {
            integrationMethod.setDescription(imDto.description());
        }
        if (imDto.limitations() != null) {
            integrationMethod.setLimitations(imDto.limitations());
        }
        if (imDto.tutorial() != null) {
            integrationMethod.setTutorial(imDto.tutorial());
        }
        if (imDto.typeIds() != null && !imDto.typeIds().isEmpty()) {
            List<IntegrationMethodType> types = integrationMethodTypeRepository.findAllById(imDto.typeIds());
            integrationMethod.setIntegMethodTypes(types);
        }

        if (connDto.existingConnectorId() != null) {
            // The author picked a connector already published in the catalog.
            connector = connectorRepository.findById(connDto.existingConnectorId())
                    .orElseThrow(() -> new RuntimeException(
                            "Connector not found: " + connDto.existingConnectorId()));
            bundle = connector.getConnectorBundle();
            isNewConnector = false;
        } else {
            // Entirely new integration method with a new connector bundle

            bundle = createConnectorBundle(connDto, username);
            connector = new Connector();
            connector.setDisplayName(connDto.displayName());
            connector.setRevision(DEFAULT_REVISION);
            ownershipService.stampNew(connector, username, connDto.maintainer());
            connector.setDescription(connDto.description());
            connector.setFullyQualifiedClassName(connDto.className());
            connector.setConnectorBundle(bundle);
        }

        // Safety net for bundles that predate the generated placeholder (or were reused from an older
        // row): bundle_name must never be empty, since it is the key the build callback matches on.
        if (bundle.getBundleName() == null || bundle.getBundleName().isBlank()) {
            bundle.setBundleName(newBundleNamePlaceholder());
        }

        return new UploadResolution(integrationMethod, connector, bundle, isNewConnector);
    }

    private ConnectorBundle createConnectorBundle(UploadConnectorDto dto, String username) {
        ConnectorBundle.FrameworkType framework = dto.framework();
        if (framework == null && dto.buildFramework() != null) {
            framework = (dto.buildFramework() == BuildFrameworkType.MAVEN)
                    ? ConnectorBundle.FrameworkType.JAVA_BASED
                    : ConnectorBundle.FrameworkType.LOW_CODE;
        }
        if (framework == null) {
            throw new IllegalArgumentException("Framework must be specified");
        }

        ConnectorBundle bundle = new ConnectorBundle();
        bundle.setRevision(DEFAULT_REVISION);
        bundle.setFramework(framework);
        bundle.setBuildFramework(dto.buildFramework());
        bundle.setLicense(dto.license() != null ? dto.license() : ConnectorBundle.LicenseType.EUPL);
        // bundle_name is the bundle's technical identity and never comes from the form. It starts as a
        // generated placeholder so the column is never empty, and the Jenkins build callback replaces it
        // with the real Maven bundle name once a build reports one (BuildCallbackService#successBuild).
        bundle.setBundleName(newBundleNamePlaceholder());
        // The form's "Connector bundle name" is the bundle's label, so it lands in display_name - and
        // it is the only thing that does. An author who leaves it empty leaves the bundle unnamed;
        // borrowing the connector's name instead would show them a bundle name they never gave.
        bundle.setDisplayName(emptyToNull(dto.bundleDisplayName()));
        ownershipService.stampNew(bundle, username, dto.maintainer());
        bundle.setTicketingLink(dto.ticketingSystemLink());
        bundle.setProjectHomepage(dto.projectHomepage());
        bundle.setGitCloneUrl(dto.gitCloneUrl());
        // description, path_to_project and build_framework are deliberately not set: the first belongs
        // to the connector, the other two to the bundle version that is built from them.
        bundle.setLifecycleState(LifecycleType.IN_REVIEW);
        return bundle;
    }

    private ConnectorBundleVersion createBundleVersion(UploadConnectorDto dto, ConnectorBundle bundle, String username) {
        String version = dto.version() != null ? dto.version() : DEFAULT_REVISION;

        if (bundle.getId() != null) {
            Optional<ConnectorBundleVersion> existing = connectorBundleVersionRepository
                    .findByConnectorBundleIdAndBundleVersion(bundle.getId(), version);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        ConnectorBundleVersion cbv = new ConnectorBundleVersion();
        cbv.setRevision(DEFAULT_REVISION);
        ownershipService.stampNew(cbv, username, dto.maintainer());
        cbv.setBundleVersion(version);
        cbv.setConnectorBundle(bundle);
        cbv.setBuildFramework(dto.buildFramework());
        cbv.setPathToProject(dto.pathToProject());
        cbv.setBrowseLink(dto.projectHomepage());
        cbv.setGitCloneUrl(dto.gitCloneUrl());
        cbv.setCommitTag(dto.commitTag());
        cbv.setLifecycleState(LifecycleType.IN_REVIEW);
        return cbv;
    }

    private ConnectorVersion createConnectorVersion(UploadConnectorDto dto, Connector connector,
                                                    ConnectorBundleVersion bundleVersion, String username) {
        ConnectorVersion cv = new ConnectorVersion();
        cv.setConnector(connector);
        cv.setConnectorBundleVersion(bundleVersion);
        cv.setRevision(DEFAULT_REVISION);
        ownershipService.stampNew(cv, username, dto.maintainer());
        cv.setFullyQualifiedClassName(dto.className());
        cv.setLifecycleState(LifecycleType.IN_REVIEW);
        return cv;
    }

    /**
     * A fresh placeholder for {@code connector_bundle.bundle_name}: a UUID string, so a bundle always has
     * a unique technical identity even before a build has told us its real Maven bundle name. Never
     * derived from user input — the form's bundle name field is a label and goes to {@code display_name}.
     */
    private String newBundleNamePlaceholder() {
        return UUID.randomUUID().toString();
    }

    private void createGitHubRepositoryIfNeeded(UploadResolution res, ConnectorBundleVersion bundleVersion,
                                                ConnectorVersion connectorVersion, List<ItemFile> files) {
        if (ConnectorBundle.FrameworkType.LOW_CODE.equals(res.bundle().getFramework())) {
            boolean hasLinks = (bundleVersion.getBrowseLink() != null && !bundleVersion.getBrowseLink().isEmpty())
                    || (bundleVersion.getGitCloneUrl() != null && !bundleVersion.getGitCloneUrl().isEmpty());
            if (!hasLinks) {
                try {
                    GithubClient githubClient = new GithubClient(githubProperties);
                    GHRepository repo = githubClient.createProjectForConnectorVersion(
                            res.integrationMethod().getDisplayName(), connectorVersion, files);
                    String browseLink = repo.getHtmlUrl().toString() + "/tree/main";
                    bundleVersion.setGitCloneUrl(repo.getHttpTransportUrl());
                    bundleVersion.setBrowseLink(browseLink);
                    res.bundle().setProjectHomepage(browseLink);
                } catch (Exception e) {
                    String msg = (e instanceof HttpException httpEx && httpEx.getResponseCode() == 401)
                            ? "Unable to create GitHub repository - bad credentials."
                            : "Unable to create GitHub repository: " + e.getMessage();
                    log.error(msg);
                    bundleVersion.setErrorMessage(msg);
                }
            }
        }
    }

    // TODO minimise Javadoc

    /**
     * Starts the build of one connector bundle version — one artifact, one build, with every class
     * on the version passed as a comma-separated {@code CONNECTOR_CLASS}. Build parameters are read
     * from the bundle version, never from the bundle, which would supply an older version's inputs.
     * {@code CONNECTOR_VERSION_*} is still sent alongside {@code CONNECTOR_BUNDLE_VERSION_*} so a
     * job that has not been updated yet still reports something the callback can resolve.
     */
    public String triggerJenkinsPipeline(ConnectorBundleVersion cbv, IntegrationMethod method) {
        try {
            ConnectorBundle bundle = cbv.getConnectorBundle();
            List<ConnectorVersion> versions = cbv.getConnectorVersions();
            //TODO rename to commitHash
            String commitHash = blankIfNull(cbv.getCommitTag());
            // The bundle is the source of truth for the clone URL; the version keeps a copy of it.
            String gitCloneUrl = blankIfNull(bundle != null && bundle.getGitCloneUrl() != null
                    ? bundle.getGitCloneUrl() : cbv.getGitCloneUrl());
            String framework = blankIfNull(bundle != null && bundle.getFramework() != null
                    ? bundle.getFramework().name() : null);
            String buildFramework = blankIfNull(cbv.getBuildFramework() != null
                    ? cbv.getBuildFramework().name() : null);
            String pathToProject = blankIfNull(cbv.getPathToProject());
            String classNames = versions.stream()
                    .map(ConnectorVersion::getFullyQualifiedClassName)
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .collect(Collectors.joining(","));
            ConnectorVersion newest = versions.stream()
                    .max(java.util.Comparator.comparingInt(ConnectorVersion::getId))
                    .orElse(null);

            JenkinsClient jenkinsClient = new JenkinsClient(jenkinsProperties);
            HttpResponse<String> response = jenkinsClient.triggerJob(
                    Map.ofEntries(
                            Map.entry("REPOSITORY_URL", gitCloneUrl),
                            Map.entry("COMMIT_HASH", commitHash),
                            Map.entry("INTEGRATION_METHOD_UUID", method.getId().toString()),
                            Map.entry("INTEGRATION_METHOD_REVISION", method.getRevision()),
                            Map.entry("INTEGRATION_METHOD_TITLE", method.getDisplayName() != null ? method.getDisplayName() : ""),
                            Map.entry("CONNECTOR_BUNDLE_VERSION_ID", String.valueOf(cbv.getId())),
                            Map.entry("CONNECTOR_BUNDLE_VERSION_REVISION", blankIfNull(cbv.getRevision())),
                            Map.entry("CONNECTOR_VERSION_ID", newest != null ? String.valueOf(newest.getId()) : ""),
                            Map.entry("CONNECTOR_VERSION_REVISION", newest != null ? blankIfNull(newest.getRevision()) : ""),
                            Map.entry("BUNDLE_FRAMEWORK", framework),
                            Map.entry("BUILD_FRAMEWORK", buildFramework),
                            Map.entry("SKIP_DEPLOY", "false"),
                            Map.entry("CONNECTOR_CLASS", classNames),
                            Map.entry("PATH_TO_PROJECT", pathToProject)));
            log.info("Jenkins job triggered for bundle version {}/{} with {} connector class(es): {}",
                    cbv.getId(), cbv.getRevision(), versions.size(), response.body());
            return response.body();
        } catch (Exception e) {
            log.error("Failed to trigger Jenkins pipeline: {}", e.getMessage());
            return e.getMessage();
        }
    }

    @Transactional
    public String editIntegrationMethod(UUID methodId, String currentRevision, EditIntegrationMethodDto dto) {
        IntegrationMethod existing = integrationMethodRepository.findById(new IntegrationMethodId(methodId, currentRevision))
                .orElseThrow(() -> new RuntimeException("Integration method not found: " + methodId + "/" + currentRevision));

        // A rejected revision is resubmitted in place (like an in-review draft), not forked into a new
        // instance
        boolean editingDraft = existing.getLifecycleState() == LifecycleType.IN_REVIEW
                || existing.getLifecycleState() == LifecycleType.REJECTED
                || existing.getLifecycleState() == LifecycleType.REVIEWING;

        if (dto.minorBump() && editingDraft) {
            // "Save" on an in-review/rejected draft: a small correction. Bump in place (2.1 -> 2.2),
            // replacing the draft row so a draft keeps a single record while it is being revised.
            String minorEdit = rewriteWithMinorBump(existing, methodId, currentRevision, dto);

            events.publishEvent(new IntegrationMethodSubmittedEvent(methodId, minorEdit,
                    SubmissionFlow.EDIT, currentRevision));
            return minorEdit;
        }

        // Otherwise spawn a fresh in-review draft, leaving the edited revision intact:
        //   - "Save" on a published revision -> minor draft (2.0 -> 2.1); on publish it replaces 2.0;
        //   - "Save as new version"          -> a brand-new major version. The number is the next
        //     available major across ALL of this method's revisions, not simply currentMajor + 1 -
        //     so "Save as new version" from 1.0 while 2.0 already exists creates 3.0, not a 2.0 clash.
        String newRevision = dto.minorBump()
                ? bumpMinorRevision(currentRevision)
                : nextMajorRevision(methodId, existing.getApplication().getId());
        IntegrationMethod clash = integrationMethodRepository
                .findById(new IntegrationMethodId(methodId, newRevision))
                .orElse(null);
        if (clash != null) {
            // A minor "Save" from a published revision whose target minor draft already exists
            // (e.g. 1.0 -> 1.1 while a 1.1 review draft is present) overwrites that in-review draft
            // with the latest edit instead of failing. A published/active clash is still refused.
            if (dto.minorBump() && clash.getLifecycleState() == LifecycleType.IN_REVIEW) {
                deleteDraft(clash, methodId);
            } else {
                throw new IllegalStateException("Revision " + newRevision + " already exists for this method; "
                        + "edit that draft instead of creating another.");
            }
        }
        createDraft(existing, dto, newRevision);
        return newRevision;
    }

    /**
     * Creates a fresh in-review draft revision of a method, copying metadata, connectors and tutorial
     * files forward and leaving the source revision intact. Used both by "Save" on a published revision
     * (minor bump) and by "Save as new version" (major bump).
     */
    private IntegrationMethod createDraft(IntegrationMethod existing, EditIntegrationMethodDto dto, String newRevision) {
        IntegrationMethod updated = createNewRevisionOfIntegrationMethod(
                existing, newRevision, dto, false);

        integrationMethodRepository.save(updated);

        if (dto != null) {
            saveIntegrationMethodCapabilities(dto.capabilities(), updated);
        } else {
            copyCapabilities(existing, updated);
        }

        // A draft forked off another revision is a submission in its own right
        events.publishEvent(new IntegrationMethodSubmittedEvent(updated.getId(), newRevision,
                dto != null && dto.minorBump() ? SubmissionFlow.EDIT : SubmissionFlow.UPGRADE, existing.getRevision()));

        return updated;
    }

    /**
     * Removes an in-review draft revision and its tutorial folder so its revision number can be
     * reused. Capabilities and connector links cascade away with the entity. The flush makes the
     * delete visible before the replacement draft is saved under the same (methodId, revision) key.
     */
    private void deleteDraft(IntegrationMethod draft, UUID methodId) {
        tutorialStorageService.deleteTutorialFolder(methodId, draft.getRevision());
        integrationMethodRepository.delete(draft);
        integrationMethodRepository.flush();
    }

    /**
     * Rewrites a revision in place with a minor bump (1.1 -> 1.2), deleting the superseded record so
     * only one survives. A rejected revision flips back to IN_REVIEW, as does anything it links.
     */
    private String rewriteWithMinorBump(IntegrationMethod existing, UUID methodId,
                                        String currentRevision, EditIntegrationMethodDto dto) {
        String newRevision = bumpMinorRevision(currentRevision);
        // Resubmitting a rejected revision flips it back to IN_REVIEW; an in-review draft stays as-is.
        boolean wasRejected = existing.getLifecycleState() == LifecycleType.REJECTED;

        IntegrationMethod updated = createNewRevisionOfIntegrationMethod(existing, newRevision, dto, true);
        if (!wasRejected) {
            updated.setLifecycleState(existing.getLifecycleState());
            // Keep the reviewer on a revision edited during its review (REVIEWING survives the rewrite);
            // a resubmitted rejected revision starts a fresh review cycle with no reviewer.
            updated.setReviewedBy(existing.getReviewedBy());
        }

        integrationMethodRepository.save(updated);
        saveIntegrationMethodCapabilities(dto.capabilities(), updated);

        // A resubmitted rejected revision had its connectors marked REJECTED; put them back to
        // IN_REVIEW so they are re-reviewed with this revision (and can be re-activated on approval).
        if (wasRejected) {
            resetRejectedConnectorsToInReview(updated);
        }

        // Drop the superseded revision; its capabilities and connector links cascade away.
        integrationMethodRepository.delete(existing);
        integrationMethodRepository.flush();

        return newRevision;
    }

    private IntegrationMethod createNewRevisionOfIntegrationMethod(
            IntegrationMethod existing, String newRevision, EditIntegrationMethodDto dto, boolean rewriteExisting) {

        IntegrationMethod updated = new IntegrationMethod();
        updated.setId(existing.getId());
        updated.setRevision(newRevision);
        updated.setApplication(existing.getApplication());
        updated.setCreatedAt(existing.getCreatedAt());
        updated.setLifecycleState(LifecycleType.IN_REVIEW);
        ownershipService.copyOwnership(existing, updated);
        if (rewriteExisting) {
            updated.setSupportTicketId(existing.getSupportTicketId());
        }
        // Supported midPoint version range comes from the edit form (prefilled from the source revision).
        updated.setMidpointMinVersionId(dto.midpointMinVersion());
        updated.setMidpointMaxVersionId(dto.midpointMaxVersion());
        updated.setAppVersion(existing.getAppVersion());
        String tutorialFolder;
        if (rewriteExisting) {
            // Move the single tutorial folder over to the bumped revision and point file_path at it.
            tutorialFolder = tutorialStorageService.renameTutorialFolder(existing.getId(), existing.getRevision(), newRevision);
        } else {
            // Carry tutorial files forward into the new revision's own folder, then point file_path at it.
            tutorialFolder = tutorialStorageService.copyTutorialFolder(existing.getId(), existing.getRevision(), newRevision);
        }
        updated.setFilePath(tutorialFolder);
        // The types the edit chose, or the ones the source revision had when it did not say. A list of
        // the new revision's own either way: two revisions may not share one collection instance.
        updated.setIntegMethodTypes(dto != null && dto.typeIds() != null
                ? new ArrayList<>(integrationMethodTypeRepository.findAllById(dto.typeIds()))
                : new ArrayList<>(existing.getIntegMethodTypes()));
        if (dto != null) {
            updated.setMidpointMinVersionId(dto.midpointMinVersion());
            updated.setMidpointMaxVersionId(dto.midpointMaxVersion());
            updated.setDisplayName(dto.displayName());
            updated.setDescription(dto.description());
            updated.setLimitations(dto.limitations());
            updated.setTutorial(dto.tutorial());
        } else {
            updated.setMidpointMinVersionId(existing.getMidpointMinVersionId());
            updated.setMidpointMaxVersionId(existing.getMidpointMaxVersionId());
            updated.setDisplayName(existing.getDisplayName());
            updated.setDescription(existing.getDescription());
            updated.setLimitations(existing.getLimitations());
            updated.setTutorial(existing.getTutorial());
        }

        copyConnectorLinks(existing, updated);

        return updated;
    }

    private void copyConnectorLinks(IntegrationMethod from, IntegrationMethod to) {
        for (IntegrationMethodConnector oldLink : from.getConnectors()) {
            IntegrationMethodConnector newLink = new IntegrationMethodConnector();
            newLink.setIntegrationMethod(to);
            newLink.setConnector(oldLink.getConnector());
            newLink.setConnectorMinVersion(oldLink.getConnectorMinVersion());
            newLink.setConnectorMaxVersion(oldLink.getConnectorMaxVersion());
            to.getConnectors().add(newLink);
        }
    }

    /**
     * Starts a review on an in-review revision: flips IN_REVIEW -> REVIEWING, which locks it for its
     * author so no changes land under the reviewer, and opens the approve/reject actions.
     */
    @Transactional
    public void startReviewIntegrationMethod(UUID methodId, String revision, String username) {
        IntegrationMethod draft = integrationMethodRepository.findById(new IntegrationMethodId(methodId, revision))
                .orElseThrow(() -> new RuntimeException("Integration method not found: " + methodId + "/" + revision));
        if (draft.getLifecycleState() != LifecycleType.IN_REVIEW) {
            throw new IllegalStateException("Only in-review revisions can be put under review: " + methodId + "/" + revision);
        }
        setConditionallyLifecycleState(draft, LifecycleType.IN_REVIEW, LifecycleType.REVIEWING);
        draft.setReviewedBy(username);
        log.info("Started review of integration method {}/{} by {}", methodId, revision, username);
    }

    private void setConditionallyLifecycleState(IntegrationMethod draft, LifecycleType inState, LifecycleType newState) {
        draft.setLifecycleState(newState);
        draft.getConnectors()
                .forEach(imc -> {
                    Connector connector = imc.getConnector();
                    setConditionallyLifecycleState(connector, inState, newState);
                });
    }

    private void setConditionallyLifecycleState(Connector connector, LifecycleType inState, LifecycleType newState) {
        ConnectorBundle bundle = connector.getConnectorBundle();
        if (bundle != null) {
            if (bundle.getLifecycleState() != inState) {
                bundle.setLifecycleState(newState);
            }
            bundle.getBundleVersions().stream()
                    .filter(bv -> bv.getLifecycleState() != inState)
                    .forEach(bv -> bv.setLifecycleState(newState));
        }
        connector.getConnectorVersions().stream()
                .filter(cv -> cv.getLifecycleState() != inState)
                .forEach(cv -> cv.setLifecycleState(newState));
    }

    /**
     * Stops an ongoing review: flips REVIEWING -> IN_REVIEW and clears the reviewer, so the
     * revision is editable again and a review can later be restarted from scratch.
     */
    @Transactional
    public void stopReviewIntegrationMethod(UUID methodId, String revision, String username) {
        IntegrationMethod draft = integrationMethodRepository.findById(new IntegrationMethodId(methodId, revision))
                .orElseThrow(() -> new RuntimeException("Integration method not found: " + methodId + "/" + revision));
        if (draft.getLifecycleState() != LifecycleType.REVIEWING) {
            throw new IllegalStateException("Only revisions under review can have the review stopped: " + methodId + "/" + revision);
        }
        setConditionallyLifecycleState(draft, LifecycleType.REVIEWING, LifecycleType.IN_REVIEW);
        draft.setReviewedBy(null);
        log.info("Stopped review of integration method {}/{} by {}", methodId, revision, username);
    }

    /**
     * Publishes (approves) an in-review revision: activates it and deletes any other ACTIVE revision
     * sharing its major version, so 2.1 supersedes 2.0 while 3.0 leaves 2.x standing.
     */
    @Transactional
    public void approveIntegrationMethod(UUID methodId, String revision, String username) {
        IntegrationMethod draft = integrationMethodRepository.findById(new IntegrationMethodId(methodId, revision))
                .orElseThrow(() -> new RuntimeException("Integration method not found: " + methodId + "/" + revision));
        if (draft.getLifecycleState() != LifecycleType.REVIEWING) {
            throw new IllegalStateException("Only reviewing revisions can be approved: " + methodId + "/" + revision);
        }

        int major = parseMajor(revision);
        UUID applicationId = draft.getApplication().getId();
        // All revisions of this method live under the same application; filter that set down to the
        // method's own revisions (the method id is stable across revisions).
        List<IntegrationMethod> superseded = integrationMethodRepository.findByApplicationId(applicationId).stream()
                .filter(m -> m.getId().equals(methodId))
                .filter(m -> !m.getRevision().equals(revision))
                .filter(m -> m.getLifecycleState() == LifecycleType.ACTIVE)
                .filter(m -> parseMajor(m.getRevision()) == major)
                .toList();

        for (IntegrationMethod old : superseded) {
            tutorialStorageService.deleteTutorialFolder(methodId, old.getRevision());
            integrationMethodRepository.delete(old);
        }
        if (!superseded.isEmpty()) {
            integrationMethodRepository.flush();
        }

        draft.setLifecycleState(LifecycleType.ACTIVE);
        draft.setReviewedBy(username);

        // Publishing the method also makes its connectors catalog-visible
        promoteConnectorsToActive(draft);

        Application application = draft.getApplication();
        if (application.getLifecycleState() != Application.ApplicationLifecycleType.ACTIVE) {
            application.setLifecycleState(Application.ApplicationLifecycleType.ACTIVE);
            applicationRepository.save(application);
            log.info("Promoted application {} to ACTIVE after publishing method {}/{}",
                    application.getId(), methodId, revision);
        }

        log.info("Published integration method {}/{} by {}; superseded {} active revision(s) of major {}",
                methodId, revision, username, superseded.size(), major);
    }

    /**
     * Activates everything beneath every connector linked to a method revision, so a published
     * method's connectors become visible. Only IN_REVIEW records are promoted.
     */
    private void promoteConnectorsToActive(IntegrationMethod method) {
        for (IntegrationMethodConnector link : method.getConnectors()) {
            Connector connector = reload(link.getConnector());
            //TODO How can connector be null?
            if (connector == null) {
                continue;
            }

            // A copy-on-write clone is folded back into the connector it came from here, so an
            // approved correction shows on EVERY method linking that connector, and a new version joins
            // the connector it belongs to. Only a clone that changed the connector identity stays a
            // separate connector and is promoted below.
            connector = mergeCloneIntoOriginal(method, link, connector);
            connector = reload(connector);

            setConditionallyLifecycleState(connector, LifecycleType.REVIEWING, LifecycleType.ACTIVE);
        }
    }

    /**
     * Mark the connectors introduced with a rejected method revision as REJECTED — the mirror of
     * promoteConnectorsToActive. Only IN_REVIEW records (newly introduced with this revision) are
     * rejected; existing ACTIVE catalog connectors reused by the method are left untouched.
     */
    private void rejectConnectorsOfMethod(IntegrationMethod method) {
        for (IntegrationMethodConnector link : method.getConnectors()) {
            Connector connector = link.getConnector();
            if (connector == null) {
                continue;
            }

            setConditionallyLifecycleState(connector, LifecycleType.REVIEWING, LifecycleType.REJECTED);
        }
    }

    /**
     * Undo a rejection on the connectors of a method: flip REJECTED records back to IN_REVIEW.
     * ACTIVE connectors the method merely reuses are left untouched.
     */
    private void resetRejectedConnectorsToInReview(IntegrationMethod method) {
        for (IntegrationMethodConnector link : method.getConnectors()) {
            Connector connector = link.getConnector();
            if (connector == null) {
                continue;
            }

            setConditionallyLifecycleState(connector, LifecycleType.REJECTED, LifecycleType.IN_REVIEW);
        }
    }

    /**
     * Reject an in-review integration method revision: mark it REJECTED and record the reviewer.
     * The revision is kept (not deleted) so the rejection and its author remain auditable.
     */
    @Transactional
    public void rejectIntegrationMethod(UUID methodId, String revision, String username) {
        IntegrationMethod draft = integrationMethodRepository.findById(new IntegrationMethodId(methodId, revision))
                .orElseThrow(() -> new RuntimeException("Integration method not found: " + methodId + "/" + revision));
        if (draft.getLifecycleState() != LifecycleType.REVIEWING) {
            throw new IllegalStateException("Only in-review revisions can be rejected: " + methodId + "/" + revision);
        }

        draft.setLifecycleState(LifecycleType.REJECTED);
        draft.setReviewedBy(username);
        // Reject the connectors introduced with this revision too (mirrors promoteConnectorsToActive).
        rejectConnectorsOfMethod(draft);
        log.info("Rejected integration method {}/{} by {}", methodId, revision, username);
    }

    private void saveIntegrationMethodCapabilities(List<IntegrationMethodCapabilityGroupDto> groups,
                                                   IntegrationMethod target) {
        if (groups == null) {
            return;
        }
        for (IntegrationMethodCapabilityGroupDto group : groups) {
            if (group.objectClass() == null || group.capabilityNames() == null || group.capabilityNames().isEmpty()) {
                continue;
            }
            // Resource-wide capabilities are the connector's; a method that still sends them is ignored.
            if (GLOBAL_OBJECT_CLASS.equalsIgnoreCase(group.objectClass())) {
                continue;
            }
            IntegrationMethodCapability cap = new IntegrationMethodCapability();
            cap.setObjectClass(group.objectClass());
            cap.setIntegrationMethod(target);
            cap = integrationMethodCapabilityRepository.save(cap);
            final Integer capId = cap.getId();
            for (String capabilityName : group.capabilityNames()) {
                capabilityRepository.findByName(capabilityName).ifPresent(capability -> {
                    IntegrationMethodCapabilityItem item = new IntegrationMethodCapabilityItem();
                    item.setIntegrationMethodCapabilityId(capId);
                    item.setCapabilityId(capability.getId());
                    integrationMethodCapabilityItemRepository.save(item);
                });
            }
        }
    }

    @Transactional
    public String addConnectorToIntegrationMethod(UUID appId, UUID methodId, String revision,
                                                  AddConnectorDto dto, String username) {
        IntegrationMethod source = integrationMethodRepository.findById(new IntegrationMethodId(methodId, revision))
                .orElseThrow(() -> new RuntimeException("Integration method not found: " + methodId + "/" + revision));

        // Checked before forking, which would otherwise copy tutorial files for nothing.
        if (dto.existingConnectorId() != null
                && source.getConnectors().stream()
                .anyMatch(link -> link.getConnector() != null
                        && dto.existingConnectorId().equals(link.getConnector().getId()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The connector is already linked to this integration method.");
        }

        boolean forkedNewDraft = source.getLifecycleState() == LifecycleType.ACTIVE;
        IntegrationMethod target;
        if (forkedNewDraft) {
            target = createDraft(source, null, nextMajorRevision(source.getId(), source.getApplication().getId()));
        } else {
            target = source;
        }

        Connector connector;
        String connectorMinVersion;

        if (dto.existingConnectorId() != null) {
            connector = connectorRepository.findById(dto.existingConnectorId())
                    .orElseThrow(() -> new RuntimeException("Connector not found: " + dto.existingConnectorId()));
            connectorMinVersion = firstNonBlank(dto.connectorVersionFrom(), connector.getRevision(), DEFAULT_REVISION);
        } else {
            UploadConnectorDto connDto = new UploadConnectorDto(
                    dto.displayName(),
                    dto.framework(),
                    dto.version(),
                    dto.license(),
                    dto.buildFramework(),
                    dto.description(),
                    dto.maintainer(),
                    dto.projectHomepage(),
                    null,
                    dto.gitCloneUrl(),
                    dto.className(),
                    dto.pathToProject(),
                    dto.commitTag(),
                    dto.bundleDisplayName(),
                    null,
                    dto.connectorVersionFrom(),
                    dto.connectorVersionTo());

            ConnectorBundle bundle = createConnectorBundle(connDto, username);
            connectorBundleRepository.save(bundle);

            ConnectorBundleVersion bundleVersion = createBundleVersion(connDto, bundle, username);
            connectorBundleVersionRepository.save(bundleVersion);

            connector = new Connector();
            connector.setDisplayName(dto.displayName());
            connector.setRevision(DEFAULT_REVISION);
            ownershipService.stampNew(connector, username, dto.maintainer());
            connector.setDescription(dto.description());
            connector.setFullyQualifiedClassName(dto.className());
            connector.setConnectorBundle(bundle);
            connectorRepository.save(connector);

            ConnectorVersion connectorVersion = createConnectorVersion(connDto, connector, bundleVersion, username);
            connectorVersionRepository.save(connectorVersion);

            connectorMinVersion = firstNonBlank(dto.connectorVersionFrom(), connectorVersion.getRevision(), DEFAULT_REVISION);
            saveConnectorVersionCapabilities(dto.connectorCapabilities(), connectorVersion);
        }

        if (dto.midpointMinVersion() != null) {
            target.setMidpointMinVersionId(dto.midpointMinVersion());
        }
        if (dto.midpointMaxVersion() != null) {
            target.setMidpointMaxVersionId(dto.midpointMaxVersion());
        }

        IntegrationMethodConnector imc = new IntegrationMethodConnector();
        imc.setConnector(connector);
        imc.setConnectorMinVersion(connectorMinVersion);
        imc.setConnectorMaxVersion(emptyToNull(dto.connectorVersionTo()));
        imc.setIntegrationMethod(target);
        target.getConnectors().add(imc);

        integrationMethodRepository.save(target);

        if (!forkedNewDraft) {
            events.publishEvent(new ConnectorAddedToReviewEvent(methodId, target.getRevision(), connector.getId()));
        }
        return target.getRevision();
    }

    /**
     * Deep-copies a method's object-class capabilities (and their capability items) onto another
     * revision, leaving behind any resource-wide group a revision from before the split still carries.
     */
    private void copyCapabilities(IntegrationMethod from, IntegrationMethod to) {
        for (IntegrationMethodCapability oldCap : from.getCapabilities()) {
            if (GLOBAL_OBJECT_CLASS.equalsIgnoreCase(oldCap.getObjectClass())) {
                continue;
            }
            IntegrationMethodCapability newCap = new IntegrationMethodCapability();
            newCap.setObjectClass(oldCap.getObjectClass());
            newCap.setIntegrationMethod(to);
            IntegrationMethodCapability saved = integrationMethodCapabilityRepository.save(newCap);
            for (IntegrationMethodCapabilityItem oldItem : oldCap.getItems()) {
                IntegrationMethodCapabilityItem item = new IntegrationMethodCapabilityItem();
                item.setIntegrationMethodCapabilityId(saved.getId());
                item.setCapabilityId(oldItem.getCapabilityId());
                integrationMethodCapabilityItemRepository.save(item);
            }
        }
    }

    /**
     * Removes a connector from an integration method revision by deleting only the link between them.
     * The connector itself may be shared with other methods, so it is left intact; orphanRemoval on the
     * method's connectors collection deletes the join row.
     */
    @Transactional
    public void deleteConnectorFromIntegrationMethod(UUID methodId, String revision, Integer connectorId) {
        IntegrationMethod method = integrationMethodRepository.findById(new IntegrationMethodId(methodId, revision))
                .orElseThrow(() -> new RuntimeException("Integration method not found: " + methodId + "/" + revision));

        IntegrationMethodConnector link = method.getConnectors().stream()
                .filter(l -> l.getConnector() != null && connectorId.equals(l.getConnector().getId()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException(
                        "Connector " + connectorId + " is not linked to integration method " + methodId + "/" + revision));

        method.getConnectors().remove(link);
        integrationMethodRepository.save(method);
    }

    /**
     * Updates the connector version range (min/max) that a given integration-method revision supports,
     * as set via the "Set up connector compatibility" modal. Only the link between the method and the
     * connector is touched; the connector itself is left unchanged.
     */
    @Transactional
    public void updateConnectorCompatibility(UUID methodId, String revision, Integer connectorId,
                                             String connectorVersionFrom, String connectorVersionTo) {
        IntegrationMethod method = integrationMethodRepository.findById(new IntegrationMethodId(methodId, revision))
                .orElseThrow(() -> new RuntimeException("Integration method not found: " + methodId + "/" + revision));

        IntegrationMethodConnector link = method.getConnectors().stream()
                .filter(l -> l.getConnector() != null && connectorId.equals(l.getConnector().getId()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException(
                        "Connector " + connectorId + " is not linked to integration method " + methodId + "/" + revision));

        String from = connectorVersionFrom == null || connectorVersionFrom.isBlank()
                ? firstNonBlank(link.getConnector().getRevision(), DEFAULT_REVISION)
                : connectorVersionFrom.trim();
        String to = connectorVersionTo == null || connectorVersionTo.isBlank() ? null : connectorVersionTo.trim();

        link.setConnectorMinVersion(from);
        link.setConnectorMaxVersion(to);
        integrationMethodRepository.save(method);
    }

    /**
     * Deep-copies a connector and everything beneath it into new rows, for copy-on-write when one
     * revision edits a connector shared with another: the edit lands on the copy and leaves the
     * original untouched. The clone takes a fresh bundle revision to keep that key unique.
     */
    private Connector cloneConnectorWithRelatedObjects(Connector src) {
        ConnectorBundle srcBundle = src.getConnectorBundle();
        ConnectorBundle bundle = ConnectorBundle.createConnectorBundleDraft(srcBundle);
        connectorBundleRepository.save(bundle);

        Connector connectorClone = Connector.createConnectorDraft(src);
        connectorRepository.save(connectorClone);

        //TODO why do we need it? We want new version (that have to be approved), but we don't need copy of all older versions?
        for (ConnectorVersion srcCv : src.getConnectorVersions()) {
            ConnectorBundleVersion srcCbv = srcCv.getConnectorBundleVersion();
            ConnectorBundleVersion cbv = null;
            //TODO how srcCbv can be null?
            if (srcCbv != null) {
                cbv = ConnectorBundleVersion.createConnectorBundleVersionDraft(srcCbv, bundle);
                connectorBundleVersionRepository.save(cbv);
            }

            ConnectorVersion cv = ConnectorVersion.createConnectorVersionDraft(srcCv, connectorClone, cbv);
            connectorVersionRepository.save(cv);

            cloneCapabilitiesOfConnectorVersion(srcCv, cv);

            connectorClone.getConnectorVersions().add(cv);
        }
        return connectorClone;
    }

    private void cloneCapabilitiesOfConnectorVersion(ConnectorVersion fromCv, ConnectorVersion toCv) {
        for (ConnVersionCapability srcCap : fromCv.getCapabilities()) {
            ConnVersionCapability cap = ConnVersionCapability.createConnVersionCapabilityDraft(srcCap, toCv);
            ConnVersionCapability savedCap = connVersionCapabilityRepository.save(cap);
            for (ConnVersionCapabilityItem srcItem : srcCap.getItems()) {
                ConnVersionCapabilityItem item =
                        ConnVersionCapabilityItem.createConnVersionCapabilityItemDraft(srcItem, savedCap);
                savedCap.getItems().add(item);
            }
            toCv.getCapabilities().add(savedCap);
        }
    }

    //TODO I need to explain this method; let’s go through it together.
    /**
     * Applies an "Edit connector" modal save. The connector version is NEVER changed automatically —
     * it has to match the Maven artifact, and catching duplicates is the reviewer's job.
     */
    @Transactional
    public void updateConnector(UUID methodId, String revision, Integer connectorId, EditConnectorDto dto,
                                String username) {
        applyConnectorEdit(methodId, revision, connectorId, dto, username);
        events.publishEvent(new IntegrationMethodSubmittedEvent(methodId, revision, SubmissionFlow.EDIT, null));
    }

    private void applyConnectorEdit(UUID methodId, String revision, Integer connectorId, EditConnectorDto dto,
                                    String username) {
        IntegrationMethod method = integrationMethodRepository.findById(new IntegrationMethodId(methodId, revision))
                .orElseThrow(() -> new RuntimeException("Integration method not found: " + methodId + "/" + revision));

        IntegrationMethodConnector link = method.getConnectors().stream()
                .filter(l -> l.getConnector() != null && connectorId.equals(l.getConnector().getId()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException(
                        "Connector " + connectorId + " is not linked to integration method " + methodId + "/" + revision));

        Connector connector = link.getConnector();

        assertFixedFieldsUnchanged(connector.getConnectorBundle(), dto);

        //TODO why when count > 1, what is different when it is only one?
        if (integrationMethodConnectorRepository.countByConnector_Id(connectorId) > 1) {
            connector = cloneConnectorWithRelatedObjects(connector);
            //TODO why we save it to original connector link?
            link.setConnector(connector);
            integrationMethodRepository.save(method);
        }

        ConnectorBundle bundle = connector.getConnectorBundle();

        ConnectorVersion baseCv = newestVersionOf(connector);
        ConnectorBundleVersion baseCbv = baseCv != null ? baseCv.getConnectorBundleVersion() : null;

        String currentVersion = firstNonBlank(
                baseCbv != null ? baseCbv.getBundleVersion() : null,
                baseCv != null ? baseCv.getRevision() : null,
                connector.getRevision(), DEFAULT_REVISION);
        //TODO I don't think that use version of connector for revision is good idea, A revision should have its own life cycle
        // mainly because what if you create version 1.2 -> then someone edits the connector, for example change the description, so you create version 1.2.1,
        // and then someone uploads connector version 1.2.1 -> what happens then?
        String requestedVersion = (dto.version() != null && !dto.version().isBlank())
                ? dto.version().trim() : currentVersion;

        boolean versionChanged = !requestedVersion.equals(currentVersion);
        String currentClassName = firstNonBlank(baseCv != null ? baseCv.getFullyQualifiedClassName() : null,
                connector.getFullyQualifiedClassName());
        String requestedClassName = firstNonBlank(dto.className(), currentClassName);
        String requestedCommitTag = firstNonBlank(dto.commitTag(),
                baseCbv != null ? baseCbv.getCommitTag() : null);
        boolean classNameChanged = identifierDiffers(dto.className(), currentClassName);
        boolean buildChanged = classNameChanged
                || identifierDiffers(dto.commitTag(), baseCbv != null ? baseCbv.getCommitTag() : null);

        connector.setDisplayName(dto.displayName());
        ownershipService.assignMaintainer(connector, dto.maintainer());
        connector.setDescription(dto.description());
        connector.setFullyQualifiedClassName(requestedClassName);
        connector.setRevision(requestedVersion);

        if (bundle != null) {
            bundle.setDisplayName(emptyToNull(dto.bundleDisplayName()));
            ownershipService.assignMaintainer(bundle, dto.maintainer());
            bundle.setTicketingLink(dto.supportPortal());
            bundle.setProjectHomepage(dto.projectHomepage());
            if (isInitialVersion(bundle)) {
                if (dto.license() != null) {
                    bundle.setLicense(dto.license());
                }
                if (dto.gitCloneUrl() != null && !dto.gitCloneUrl().isBlank()) {
                    bundle.setGitCloneUrl(dto.gitCloneUrl().trim());
                }
            }
            connectorBundleRepository.save(bundle);
        }

        //TODO Do we really save changes without approval?
        if (!versionChanged && !classNameChanged) {
            if (baseCv != null) {
                applyVersionEdit(baseCv, baseCbv, dto, requestedClassName, requestedCommitTag, null);
            }
            connectorRepository.save(connector);
            return;
        }

        //TODO I'm not sure if using the latest version of the connector is a good idea.
        // The GUI shows the specific version, so we can use the version that the user edited.
        ConnectorVersion target = connector.getConnectorVersions().stream()
                .filter(v -> v.getConnectorBundleVersion() != null)
                .filter(v -> requestedVersion.equals(firstNonBlank(
                        v.getConnectorBundleVersion().getBundleVersion(), v.getRevision())))
                .max(java.util.Comparator.comparingInt(ConnectorVersion::getId))
                .orElse(null);

        if (target != null) {
            String errorMessage = versionChanged
                    ? "Duplicate version with (" + connector.getDisplayName() + " " + requestedVersion + ")"
                    : null;
            applyVersionEdit(target, target.getConnectorBundleVersion(), dto,
                    requestedClassName, requestedCommitTag, errorMessage);
        } else {
            String errorMessage = !buildChanged
                    ? "Duplicate version with (" + connector.getDisplayName() + " " + currentVersion + ")"
                    : null;

            ConnectorBundleVersion cbv = bundle == null || bundle.getId() == null ? null
                    : connectorBundleVersionRepository
                    .findByConnectorBundleIdAndBundleVersion(bundle.getId(), requestedVersion)
                    .orElse(null);
            if (cbv == null) {
                cbv = new ConnectorBundleVersion();
                cbv.setRevision(requestedVersion);
                cbv.setBundleVersion(requestedVersion);
                cbv.setConnectorBundle(bundle);
                ownershipService.stampNew(cbv, username, dto.maintainer());
                cbv.setLifecycleState(LifecycleType.IN_REVIEW);
                cbv.setBrowseLink(dto.projectHomepage());  // one link, not two - see createBundleVersion
                cbv.setPathToProject(firstNonBlank(dto.pathToProject(),
                        baseCbv != null ? baseCbv.getPathToProject() : null));
                cbv.setCommitTag(requestedCommitTag);
                cbv.setBuildFramework(dto.buildFramework() != null ? dto.buildFramework()
                        : (baseCbv != null ? baseCbv.getBuildFramework() : null));
                cbv.setGitCloneUrl(bundle != null ? bundle.getGitCloneUrl()
                        : (baseCbv != null ? baseCbv.getGitCloneUrl() : null));
                connectorBundleVersionRepository.save(cbv);
            }

            ConnectorVersion cv = new ConnectorVersion();
            cv.setConnector(connector);
            cv.setConnectorBundleVersion(cbv);
            cv.setRevision(requestedVersion);
            ownershipService.stampNew(cv, username, dto.maintainer());
            cv.setFullyQualifiedClassName(requestedClassName);
            cv.setLifecycleState(LifecycleType.IN_REVIEW);
            cv.setErrorMessage(errorMessage);
            connectorVersionRepository.save(cv);
            connector.getConnectorVersions().add(cv);

            saveConnectorVersionCapabilities(dto.connectorCapabilities(), cv);
        }

        //TODO Do we really save changes without approval?
        connectorRepository.save(connector);
    }

    /**
     * The connector's current version row: ids are sequence-assigned, so max id is the newest.
     */
    private static ConnectorVersion newestVersionOf(Connector connector) {
        return connector.getConnectorVersions().stream()
                .filter(cv -> cv.getConnectorBundleVersion() != null)
                .max(java.util.Comparator.comparingInt(ConnectorVersion::getId))
                .orElse(null);
    }

    /**
     * Folds a copy-on-write clone back into the connector it was cloned from, when the two still live
     * in the same bundle; only a clone that moved to a different bundle stays standing beside it.
     *
     * @return the connector the method is linked to afterwards
     */
    private Connector mergeCloneIntoOriginal(IntegrationMethod method,
                                             IntegrationMethodConnector link, Connector clone) {
        Integer originId = clone.getClonedFrom();
        if (originId == null) {
            return clone;
        }

        Connector original = connectorRepository.findById(originId).orElse(null);
        //TODO why? This situation should not arise
        if (original == null || original.getId().equals(clone.getId())) {
            clone.setClonedFrom(null);
            connectorRepository.save(clone);
            return clone;
        }

        ConnectorVersion cloneCv = newestVersionOf(clone);
        //TODO I think that it isn't ok because sometime somewhere can edit old version of connector
        ConnectorVersion origCv = newestVersionOf(original);

        //TODO duplicate of code
        String cloneVersion = firstNonBlank(
                cloneCv != null && cloneCv.getConnectorBundleVersion() != null
                        ? cloneCv.getConnectorBundleVersion().getBundleVersion() : null,
                cloneCv != null ? cloneCv.getRevision() : null, clone.getRevision());
        String origVersion = firstNonBlank(
                origCv != null && origCv.getConnectorBundleVersion() != null
                        ? origCv.getConnectorBundleVersion().getBundleVersion() : null,
                origCv != null ? origCv.getRevision() : null, original.getRevision());

        ConnectorBundle cloneBundle = clone.getConnectorBundle();
        ConnectorBundle origBundle = original.getConnectorBundle();
        //TODO cloneBundle and origBundle can be null?
        //TODO for same connectors compare connector.fullyQualifiedClassName or rename sameConnector to sameBundle
        boolean sameConnector = Objects.equals(cloneBundle != null ? cloneBundle.getBundleName() : null,
                origBundle != null ? origBundle.getBundleName() : null);
        if (cloneVersion == null || !sameConnector) {
            return clone;
        }
        if (!Objects.equals(cloneVersion, origVersion)) {
            return absorbOriginalIntoClone(clone, cloneBundle, original, origBundle);
        }

        original.setDisplayName(clone.getDisplayName());
        ownershipService.copyMaintainer(clone, original);
        original.setDescription(clone.getDescription());
        original.setFullyQualifiedClassName(clone.getFullyQualifiedClassName());
        original.setRevision(clone.getRevision());
        if (origBundle != null && cloneBundle != null) {
            origBundle.setDisplayName(cloneBundle.getDisplayName());
            ownershipService.copyMaintainer(cloneBundle, origBundle);
            origBundle.setTicketingLink(cloneBundle.getTicketingLink());
            origBundle.setProjectHomepage(cloneBundle.getProjectHomepage());
            connectorBundleRepository.save(origBundle);
        }
        if (origCv != null && cloneCv != null) {
            // Same version: rewrite the original's matching build rows with the corrected values.
            ownershipService.copyMaintainer(cloneCv, origCv);
            origCv.setFullyQualifiedClassName(cloneCv.getFullyQualifiedClassName());
            ConnectorBundleVersion origCbv = origCv.getConnectorBundleVersion();
            ConnectorBundleVersion cloneCbv = cloneCv.getConnectorBundleVersion();
            if (origCbv != null && cloneCbv != null) {
                ownershipService.copyMaintainer(cloneCbv, origCbv);
                origCbv.setBrowseLink(cloneCbv.getBrowseLink());
                origCbv.setPathToProject(cloneCbv.getPathToProject());
                origCbv.setCommitTag(cloneCbv.getCommitTag());
                origCbv.setBuildFramework(cloneCbv.getBuildFramework());
                connectorBundleVersionRepository.save(origCbv);
            }
            deleteCapabilitiesOfConnectorVersion(origCv);
            cloneCapabilitiesOfConnectorVersion(cloneCv, origCv);
            connectorVersionRepository.save(origCv);
        }
        connectorRepository.save(original);

        link.setConnector(original);
        integrationMethodRepository.save(method);
        connectorRepository.delete(clone);
        if (cloneBundle != null) {
            connectorBundleRepository.delete(cloneBundle);
        }
        log.info("Folded metadata-edit clone {} back into connector {} on approval",
                clone.getId(), original.getId());
        return original;
    }

    /**
     * Retires the original connector in favour of a version-bump clone: the clone is already the
     * complete graph, so everything still pointing at the original is moved onto it and the
     * original is deleted.
     */
    private Connector absorbOriginalIntoClone(Connector clone, ConnectorBundle cloneBundle,
                                              Connector original, ConnectorBundle origBundle) {
        copyMissingVersions(original, clone, cloneBundle);

        moveDownloads(origBundle, cloneBundle);

        for (IntegrationMethodConnector otherLink
                : integrationMethodConnectorRepository.findByConnector_Id(original.getId())) {
            otherLink.setConnector(clone);
            integrationMethodConnectorRepository.save(otherLink);
        }

        for (Connector sibling : connectorRepository.findByClonedFrom(original.getId())) {
            if (!sibling.getId().equals(clone.getId())) {
                sibling.setClonedFrom(clone.getId());
                connectorRepository.save(sibling);
            }
        }

        connectorRepository.delete(original);
        connectorRepository.flush();

        if (origBundle != null && cloneBundle != null && !Objects.equals(origBundle.getId(), cloneBundle.getId())) {
            retireBundleInto(origBundle, cloneBundle);
        } else if (origBundle != null) {
            connectorBundleRepository.delete(origBundle);
            connectorBundleRepository.flush();
        }

        Connector survivor = reload(clone);
        survivor.setClonedFrom(null);
        connectorRepository.save(survivor);
        log.info("Version-bump clone {} replaced connector {} on approval", survivor.getId(), original.getId());
        return survivor;
    }

    /**
     * Empties the retired connector's bundle into the surviving clone's bundle and deletes it, so the
     * catalog never holds two bundles claiming the same {@code bundle_name}.
     */
    private void retireBundleInto(ConnectorBundle source, ConnectorBundle target) {
        Map<String, ConnectorBundleVersion> twins = mapOfConnectorVersionsByVersion(target);
        List<ConnectorBundleVersion> sourceVersions = List.copyOf(source.getBundleVersions());

        int shared = 0;
        for (ConnectorBundleVersion cbv : sourceVersions) {
            String key = versionKeyOf(cbv);
            ConnectorBundleVersion twin = key != null ? twins.get(key) : null;
            if (twin == null) {
                continue;
            }
            connectorVersionRepository.moveAllToBundleVersion(cbv, twin);
            connectorBundleVersionRepository.deleteRow(cbv.getId(), cbv.getRevision());
            shared++;
        }
        connectorBundleVersionRepository.moveAllToBundle(source, target);
        int connectors = connectorRepository.moveAllToBundle(source, target);
        connectorBundleRepository.deleteRow(source.getId());

        log.info("Retired connector bundle {} into {} ({}): moved {} sibling connector(s), "
                        + "{} of {} version(s) merged into an existing one",
                source.getId(), target.getId(), target.getBundleName(),
                connectors, shared, sourceVersions.size());
    }

    /**
     * Fetches a connector again, so it is managed with loadable collections. A no-op for one that
     * already is; it matters after {@link #retireBundleInto}, whose bulk updates clear the persistence
     * context and detach every connector loaded before them.
     */
    private Connector reload(Connector connector) {
        if (connector == null || connector.getId() == null) {
            return connector;
        }
        return connectorRepository.findById(connector.getId()).orElse(connector);
    }

    /**
     * Indexes a bundle's versions by the key its versions are matched on across bundles.
     */
    private static Map<String, ConnectorBundleVersion> mapOfConnectorVersionsByVersion(ConnectorBundle bundle) {
        Map<String, ConnectorBundleVersion> index = new HashMap<>();
        if (bundle == null) {
            return index;
        }
        for (ConnectorBundleVersion cbv : bundle.getBundleVersions()) {
            String key = versionKeyOf(cbv);
            if (key != null) {
                index.putIfAbsent(key, cbv);
            }
        }
        return index;
    }

    /**
     * Repoints the download history of {@code from}'s bundle versions onto {@code to}'s matching ones.
     */
    private void moveDownloads(ConnectorBundle from, ConnectorBundle to) {
        if (from == null || to == null) {
            return;
        }
        Map<String, ConnectorBundleVersion> targets = mapOfConnectorVersionsByVersion(to);
        for (ConnectorBundleVersion cbv : from.getBundleVersions()) {
            String key = versionKeyOf(cbv);
            ConnectorBundleVersion target = key != null ? targets.get(key) : null;
            if (target == null) {
                continue;
            }
            for (Download download : downloadRepository.findByConnectorBundleVersion(cbv)) {
                download.setConnectorBundleVersion(target);
                downloadRepository.save(download);
            }
        }
    }

    /**
     * Copies every version {@code from} carries that {@code to} does not onto {@code to} and its bundle.
     */
    private void copyMissingVersions(Connector from, Connector to, ConnectorBundle toBundle) {
        Set<String> existing = to.getConnectorVersions().stream()
                .map(ConnectorUploadService::versionKeyOf)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        //TODO why ordering?
        List<ConnectorVersion> ordered = from.getConnectorVersions().stream()
                .sorted(java.util.Comparator.comparingInt(ConnectorVersion::getId))
                .toList();

        for (ConnectorVersion srcCv : ordered) {
            String version = versionKeyOf(srcCv);
            if (version == null || !existing.add(version)) {
                continue;
            }

            ConnectorBundleVersion srcCbv = srcCv.getConnectorBundleVersion();
            ConnectorBundleVersion cbv = null;
            if (srcCbv != null && toBundle != null) {
                cbv = ConnectorBundleVersion.createConnectorBundleVersion(srcCbv, toBundle);
                cbv = connectorBundleVersionRepository.save(cbv);
                toBundle.getBundleVersions().add(cbv);
            }

            ConnectorVersion cv = ConnectorVersion.createConnectorVersion(srcCv, to, cbv);
            cv = connectorVersionRepository.save(cv);
            to.getConnectorVersions().add(cv);

            deleteCapabilitiesOfConnectorVersion(cv);
            cloneCapabilitiesOfConnectorVersion(srcCv, cv);
            connectorVersionRepository.save(cv);
        }
    }

    /**
     * The user-facing version a connector version row carries: its bundle version, else its revision.
     */
    private static String versionKeyOf(ConnectorVersion cv) {
        if (cv == null) {
            return null;
        }
        ConnectorBundleVersion cbv = cv.getConnectorBundleVersion();
        return firstNonBlank(cbv != null ? cbv.getBundleVersion() : null, cv.getRevision());
    }

    /**
     * The same key read off the bundle version row itself.
     */
    private static String versionKeyOf(ConnectorBundleVersion cbv) {
        if (cbv == null) {
            return null;
        }
        return firstNonBlank(cbv.getBundleVersion(), cbv.getRevision());
    }

    private void deleteCapabilitiesOfConnectorVersion(ConnectorVersion connectorVersion) {
        if (connectorVersion.getCapabilities() != null && !connectorVersion.getCapabilities().isEmpty()) {
            connVersionCapabilityRepository.deleteAll(connectorVersion.getCapabilities());
            connectorVersion.getCapabilities().clear();
        }
    }

    /**
     * Rewrites an existing connector version (+ its bundle version) with the edited build data. The
     * maintainer is not among it: it describes the connector, not a build.
     *
     * @param className the resolved class name (blank means "unchanged", never "clear it")
     * @param commitTag the resolved commit hash, resolved the same way
     */
    private void applyVersionEdit(ConnectorVersion cv, ConnectorBundleVersion cbv, EditConnectorDto dto,
                                  String className, String commitTag, String errorMessage) {
        ownershipService.assignMaintainer(cv, dto.maintainer());
        cv.setFullyQualifiedClassName(className);
        cv.setErrorMessage(errorMessage);
        if (cbv != null) {
            ownershipService.assignMaintainer(cbv, dto.maintainer());
            cbv.setBrowseLink(dto.projectHomepage());  // one link, not two - see createBundleVersion
            cbv.setPathToProject(firstNonBlank(dto.pathToProject(), cbv.getPathToProject()));
            cbv.setCommitTag(commitTag);
            if (dto.buildFramework() != null) {
                cbv.setBuildFramework(dto.buildFramework());
            }
            connectorBundleVersionRepository.save(cbv);
        }
        connectorVersionRepository.save(cv);
        replaceConnectorVersionCapabilities(cv, dto.connectorCapabilities());
    }

    private void replaceConnectorVersionCapabilities(ConnectorVersion connectorVersion,
                                                     List<IntegrationMethodCapabilityGroupDto> groups) {
        // Remove existing capabilities (items cascade away via orphanRemoval)
        deleteCapabilitiesOfConnectorVersion(connectorVersion);
        saveConnectorVersionCapabilities(groups, connectorVersion);
    }

    /**
     * Whether the bundle is still on its first version — the state in which the author is filling the
     * bundle in rather than changing what consumers of an earlier version already have.
     */
    private static boolean isInitialVersion(ConnectorBundle bundle) {
        return bundle == null || bundle.getBundleVersions().size() <= 1;
    }

    /**
     * Rejects an edit that would change what identifies the bundle rather than describe it: the
     * license every consumer of an earlier version already accepted, and the repository the artifact
     * is built from. Both are settled with the first version and fixed afterwards.
     */
    private static void assertFixedFieldsUnchanged(ConnectorBundle bundle, EditConnectorDto dto) {
        if (isInitialVersion(bundle)) {
            return;
        }
        if (dto.license() != null && bundle.getLicense() != null && dto.license() != bundle.getLicense()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The license is fixed after the first version of a connector bundle.");
        }
        if (identifierDiffers(dto.gitCloneUrl(), bundle.getGitCloneUrl())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The git clone URL is fixed after the first version of a connector bundle.");
        }
    }

    private static boolean identifierDiffers(String incoming, String current) {
        if (incoming == null || incoming.isBlank()) {
            return false;
        }
        return !incoming.trim().equals(current == null ? "" : current.trim());
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /**
     * Jenkins job parameters are a Map of non-null strings, so a missing value goes as "".
     */
    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /**
     * Next available major version for a method: one higher than the largest major across ALL of the
     * method's existing revisions, with the minor reset to 0. Creating a new version from any base
     * revision therefore never collides with an existing major (1.0 + 2.0 present -> 3.0).
     */
    private String nextMajorRevision(UUID methodId, UUID applicationId) {
        int maxMajor = integrationMethodRepository.findByApplicationId(applicationId).stream()
                .filter(m -> m.getId().equals(methodId))
                .mapToInt(m -> parseMajor(m.getRevision()))
                .max()
                .orElse(0);
        return (maxMajor + 1) + ".0";
    }

    /**
     * Minor bump: keeps the major segment and increments the minor (1.1 -> 1.2, "1" -> "1.1").
     */
    private String bumpMinorRevision(String revision) {
        return parseMajor(revision) + "." + (parseMinor(revision) + 1);
    }

    private int parseMajor(String revision) {
        if (revision == null || revision.isBlank()) {
            return 1;
        }
        try {
            return Integer.parseInt(revision.split("\\.")[0]);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private int parseMinor(String revision) {
        if (revision == null || revision.isBlank()) {
            return 0;
        }
        String[] parts = revision.split("\\.");
        if (parts.length < 2) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void saveIntegrationMethodCapabilities(UploadIntegrationDto dto, IntegrationMethod integrationMethod) {
        saveIntegrationMethodCapabilities(dto.integrationMethodCapabilities(), integrationMethod);
    }

    private void saveConnectorVersionCapabilities(UploadIntegrationDto dto, ConnectorVersion connectorVersion) {
        saveConnectorVersionCapabilities(dto.connectorCapabilities(), connectorVersion);
    }

    private void saveConnectorVersionCapabilities(List<IntegrationMethodCapabilityGroupDto> groups, ConnectorVersion connectorVersion) {
        if (groups == null || groups.isEmpty()) {
            return;
        }

        for (IntegrationMethodCapabilityGroupDto group : groups) {
            if (group.objectClass() == null || group.capabilityNames() == null || group.capabilityNames().isEmpty()) {
                continue;
            }

            ConnVersionCapability cap = new ConnVersionCapability();
            cap.setObjectClass(group.objectClass());
            cap.setConnectorVersion(connectorVersion);
            cap = connVersionCapabilityRepository.save(cap);

            final Integer capId = cap.getId();
            for (String capabilityName : group.capabilityNames()) {
                capabilityRepository.findByName(capabilityName).ifPresent(capability -> {
                    ConnVersionCapabilityItem item = new ConnVersionCapabilityItem();
                    item.setConnVersionCapabilityId(capId);
                    item.setCapabilityId(capability.getId());
                    connVersionCapabilityItemRepository.save(item);
                });
            }
        }
    }

    private void persistEntities(ApplicationResolution appRes, UploadResolution uploadRes) {
        applicationRepository.save(appRes.application());
        integrationMethodRepository.save(uploadRes.integrationMethod());
    }

    private void persistEntities(UploadResolution uploadRes,
                                 ConnectorBundleVersion bundleVersion, ConnectorVersion connectorVersion) {
        if (uploadRes.bundle().getId() == null) {
            connectorBundleRepository.save(uploadRes.bundle());
        }
        if (bundleVersion.getId() == null) {
            connectorBundleVersionRepository.save(bundleVersion);
        }
        connectorRepository.save(uploadRes.connector());
        connectorVersionRepository.save(connectorVersion);
    }
}
