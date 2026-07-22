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
import com.evolveum.midpoint.integration.catalog.dto.UploadImplementationDto;
import com.evolveum.midpoint.integration.catalog.dto.UploadIntegrationMethodDto;
import com.evolveum.midpoint.integration.catalog.integration.GithubClient;
import com.evolveum.midpoint.integration.catalog.integration.JenkinsClient;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.evolveum.midpoint.integration.catalog.repository.*;
import com.evolveum.midpoint.integration.catalog.object.ConnVersionCapability;
import com.evolveum.midpoint.integration.catalog.object.ConnVersionCapabilityItem;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.HttpException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorUploadService {

    private final ApplicationRepository applicationRepository;
    private final IntegrationMethodRepository integrationMethodRepository;
    private final ConnectorRepository connectorRepository;
    private final ConnectorVersionRepository connectorVersionRepository;
    private final ConnectorBundleRepository connectorBundleRepository;
    private final ConnectorBundleVersionRepository connectorBundleVersionRepository;
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

    private record ApplicationResolution(Application application, boolean isNew,
                                          List<String> originNames, List<ApplicationTagDto> tagDtos) {}

    private record UploadResolution(IntegrationMethod integrationMethod, Connector connector,
                                     ConnectorBundle bundle, boolean isNewVersion) {}

    @Transactional
    public String uploadConnector(UploadImplementationDto dto, String username) {
        ApplicationResolution appRes = resolveApplication(dto);
        UploadResolution uploadRes = resolveUpload(dto, appRes.application(), username);

        if (!uploadRes.isNewVersion()) {
            uploadRes.integrationMethod().setAuthor(username);
            uploadRes.integrationMethod().setMaintainer(dto.connector().maintainer());
        }

        ConnectorBundleVersion bundleVersion = createBundleVersion(dto.connector(), uploadRes.bundle(), username);
        ConnectorVersion connectorVersion = createConnectorVersion(
                dto.connector(), uploadRes.connector(), bundleVersion, username);

        applicationTagService.processOrigins(appRes.application(), appRes.originNames(), appRes.isNew());
        applicationTagService.processTags(appRes.application(), appRes.tagDtos(), appRes.isNew());

        setUpRelationships(uploadRes, bundleVersion);
        setDefaults(appRes.application(), uploadRes.bundle(), bundleVersion, connectorVersion);
        copyFromLatestVersionIfNeeded(uploadRes, bundleVersion, connectorVersion);
        createGitHubRepositoryIfNeeded(uploadRes, bundleVersion, connectorVersion, dto.files());

        persistEntities(appRes, uploadRes, bundleVersion, connectorVersion);
        saveIntegrationMethodCapabilities(dto, uploadRes.integrationMethod());
        saveConnectorVersionCapabilities(dto, connectorVersion);
        triggerJenkinsPipeline(connectorVersion, uploadRes.integrationMethod(), uploadRes.bundle(), dto.connector());

        return appRes.application().getId() + "|" + uploadRes.integrationMethod().getId();
    }

    private ApplicationResolution resolveApplication(UploadImplementationDto dto) {
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
        }
        return new ApplicationResolution(application, isNew, originNames, tagDtos);
    }

    private UploadResolution resolveUpload(UploadImplementationDto dto, Application application, String username) {
        UploadIntegrationMethodDto imDto = dto.integrationMethod();
        UploadConnectorDto connDto = dto.connector();
        boolean isNewVersion = false;

        IntegrationMethod integrationMethod;
        Connector connector;
        ConnectorBundle bundle;

        if (imDto.id() != null) {
            // Adding a new version to an existing integration method
            integrationMethod = integrationMethodRepository.findByApplicationId(application.getId()).stream()
                    .filter(m -> m.getId().equals(imDto.id()))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Integration method not found: " + imDto.id()));
            isNewVersion = true;
            // Reuse existing connector link
            connector = integrationMethod.getConnectors().isEmpty() ? null
                    : integrationMethod.getConnectors().get(0).getConnector();
            bundle = connector != null ? connector.getConnectorBundle() : createNewConnectorBundle(connDto, username);
        } else if (connDto.connectorBundleId() != null) {
            // User chose an existing connector as a template — create a new independent bundle
            // so the new IN_REVIEW connector is isolated from the existing ACTIVE one
            ConnectorBundle templateBundle = connectorBundleRepository.findById(connDto.connectorBundleId())
                    .orElseThrow(() -> new RuntimeException("Connector bundle not found: " + connDto.connectorBundleId()));
            bundle = createNewConnectorBundle(connDto, templateBundle, username);
            integrationMethod = new IntegrationMethod();
            integrationMethod.setApplication(application);
            integrationMethod.setLifecycleState(LifecycleType.IN_REVIEW);
            connector = new Connector();
            connector.setDisplayName(connDto.displayName());
            connector.setRevision("1.0.0");
            connector.setAuthor(username);
            connector.setMaintainer(connDto.maintainer());
            connector.setDescription(connDto.description());
            connector.setFullyQualifiedClassName(connDto.className());
            connector.setConnectorBundle(bundle);
        } else {
            // Entirely new integration method with a new connector bundle
            integrationMethod = new IntegrationMethod();
            integrationMethod.setApplication(application);
            integrationMethod.setLifecycleState(LifecycleType.IN_REVIEW);

            bundle = createNewConnectorBundle(connDto, username);
            connector = new Connector();
            connector.setDisplayName(connDto.displayName());
            connector.setRevision("1.0.0");
            connector.setAuthor(username);
            connector.setMaintainer(connDto.maintainer());
            connector.setDescription(connDto.description());
            connector.setFullyQualifiedClassName(connDto.className());
            connector.setConnectorBundle(bundle);
        }

        integrationMethod.setMidpointMinVersionId(imDto.midpointMinVersion());
        integrationMethod.setMidpointMaxVersionId(imDto.midpointMaxVersion());

        if (!isNewVersion) {
            if (imDto.displayName() != null) {
                integrationMethod.setDisplayName(imDto.displayName());
            }
            if (imDto.revision() != null) {
                integrationMethod.setRevision(imDto.revision());
            }
            if (imDto.description() != null) {
                integrationMethod.setDescription(imDto.description());
            }
            if (imDto.tutorial() != null) {
                integrationMethod.setTutorial(imDto.tutorial());
            }
            if (imDto.typeIds() != null && !imDto.typeIds().isEmpty()) {
                List<IntegrationMethodType> types = integrationMethodTypeRepository.findAllById(imDto.typeIds());
                integrationMethod.setIntegMethodTypes(types);
            }
        }

        return new UploadResolution(integrationMethod, connector, bundle, isNewVersion);
    }

    private ConnectorBundle createNewConnectorBundle(UploadConnectorDto dto, String username) {
        ConnectorBundle.FrameworkType framework = dto.framework();
        if (framework == null && dto.buildFramework() != null) {
            framework = (dto.buildFramework() == BuildFrameworkType.MAVEN)
                    ? ConnectorBundle.FrameworkType.JAVA_BASED
                    : ConnectorBundle.FrameworkType.LOW_CODE;
        }
        if (framework == null) {
            throw new IllegalArgumentException("Framework must be specified");
        }

        BuildFrameworkType buildFramework = dto.buildFramework();

        ConnectorBundle bundle = new ConnectorBundle();
        bundle.setRevision("1.0.0");
        bundle.setAuthor(username);
        bundle.setFramework(framework);
        bundle.setBuildFramework(buildFramework);
        bundle.setLicense(dto.license() != null ? dto.license() : ConnectorBundle.LicenseType.APACHE_2);
        bundle.setBundleName(dto.bundleName());
        bundle.setDisplayName(dto.bundleDisplayName());
        bundle.setDescription(dto.description());
        bundle.setMaintainer(dto.maintainer());
        bundle.setTicketingLink(dto.ticketingSystemLink());
        bundle.setProjectHomepage(dto.browseLink());
        bundle.setGitCloneUrl(dto.gitCloneUrl());
        bundle.setPathToProject(dto.pathToProject());
        bundle.setLifecycleState(LifecycleType.IN_REVIEW);
        return bundle;
    }

    private ConnectorBundle createNewConnectorBundle(UploadConnectorDto dto, ConnectorBundle template, String username) {
        ConnectorBundle.FrameworkType framework = dto.framework() != null ? dto.framework() : template.getFramework();
        BuildFrameworkType buildFramework = dto.buildFramework() != null ? dto.buildFramework() : template.getBuildFramework();
        if (framework == null && buildFramework != null) {
            framework = (buildFramework == BuildFrameworkType.MAVEN)
                    ? ConnectorBundle.FrameworkType.JAVA_BASED
                    : ConnectorBundle.FrameworkType.LOW_CODE;
        }
        if (framework == null) {
            throw new IllegalArgumentException("Framework must be specified");
        }

        ConnectorBundle bundle = new ConnectorBundle();
        bundle.setRevision("1.0.0");
        bundle.setAuthor(username);
        bundle.setFramework(framework);
        bundle.setBuildFramework(buildFramework);
        bundle.setLicense(dto.license() != null ? dto.license() : ConnectorBundle.LicenseType.APACHE_2);
        bundle.setBundleName(dto.bundleName());
        bundle.setDisplayName(dto.bundleDisplayName());
        bundle.setDescription(dto.description());
        bundle.setMaintainer(dto.maintainer());
        bundle.setTicketingLink(dto.ticketingSystemLink());
        bundle.setProjectHomepage(dto.browseLink());
        bundle.setGitCloneUrl(dto.gitCloneUrl());
        bundle.setPathToProject(dto.pathToProject());
        bundle.setLifecycleState(LifecycleType.IN_REVIEW);
        return bundle;
    }

    private ConnectorBundleVersion createBundleVersion(UploadConnectorDto dto, ConnectorBundle bundle, String username) {
        String version = dto.version() != null ? dto.version() : "1.0.0";

        if (bundle.getId() != null) {
            Optional<ConnectorBundleVersion> existing = connectorBundleVersionRepository
                    .findByConnectorBundleIdAndBundleVersion(bundle.getId(), version);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        ConnectorBundleVersion cbv = new ConnectorBundleVersion();
        cbv.setRevision(version);
        cbv.setAuthor(username);
        cbv.setMaintainer(dto.maintainer());
        cbv.setBundleVersion(version);
        cbv.setConnectorBundle(bundle);
        cbv.setBuildFramework(dto.buildFramework());
        cbv.setPathToProject(dto.pathToProject());
        cbv.setBrowseLink(dto.browseLink());
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
        cv.setRevision(dto.version() != null ? dto.version() : "1.0.0");
        cv.setAuthor(username);
        cv.setMaintainer(dto.maintainer());
        cv.setFullyQualifiedClassName(dto.className());
        cv.setLifecycleState(LifecycleType.IN_REVIEW);
        return cv;
    }

    private void setUpRelationships(UploadResolution res, ConnectorBundleVersion bundleVersion) {
        if (!res.isNewVersion()) {
            res.connector().setConnectorBundle(res.bundle());
        }
        bundleVersion.setConnectorBundle(res.bundle());
    }

    private void setDefaults(Application application, ConnectorBundle bundle,
                              ConnectorBundleVersion bundleVersion, ConnectorVersion connectorVersion) {
        if (application.getLifecycleState() == null) {
            application.setLifecycleState(Application.ApplicationLifecycleType.IN_REVIEW);
        }
        if (bundle.getBundleName() == null || bundle.getBundleName().isBlank()) {
            bundle.setBundleName(UUID.randomUUID().toString());
        }
    }

    private void copyFromLatestVersionIfNeeded(UploadResolution res, ConnectorBundleVersion bundleVersion,
                                                ConnectorVersion connectorVersion) {
        if (!res.isNewVersion() || res.connector() == null) return;

        List<ConnectorVersion> existing = res.connector().getConnectorVersions();
        if (existing == null || existing.isEmpty()) return;

        ConnectorVersion latest = existing.stream()
                .max((a, b) -> {
                    if (a.getUpdated() == null) return -1;
                    if (b.getUpdated() == null) return 1;
                    return a.getUpdated().compareTo(b.getUpdated());
                })
                .orElse(null);

        if (latest == null) return;

        if (connectorVersion.getFullyQualifiedClassName() == null && latest.getFullyQualifiedClassName() != null) {
            connectorVersion.setFullyQualifiedClassName(latest.getFullyQualifiedClassName());
        }

        ConnectorBundleVersion latestCbv = latest.getConnectorBundleVersion();
        if (latestCbv != null) {
            if (bundleVersion.getBrowseLink() == null || bundleVersion.getBrowseLink().isEmpty()) {
                bundleVersion.setBrowseLink(latestCbv.getBrowseLink());
            }
            if (bundleVersion.getGitCloneUrl() == null || bundleVersion.getGitCloneUrl().isEmpty()) {
                bundleVersion.setGitCloneUrl(latestCbv.getGitCloneUrl());
            }
        }
    }

    private void createGitHubRepositoryIfNeeded(UploadResolution res, ConnectorBundleVersion bundleVersion,
                                                  ConnectorVersion connectorVersion, List<ItemFile> files) {
        if (res.isNewVersion()) return;

        if (ConnectorBundle.FrameworkType.LOW_CODE.equals(res.bundle().getFramework())) {
            boolean hasLinks = (bundleVersion.getBrowseLink() != null && !bundleVersion.getBrowseLink().isEmpty())
                    || (bundleVersion.getGitCloneUrl() != null && !bundleVersion.getGitCloneUrl().isEmpty());
            if (!hasLinks) {
                try {
                    GithubClient githubClient = new GithubClient(githubProperties);
                    GHRepository repo = githubClient.createProjectForConnectorVersion(
                            res.integrationMethod().getDisplayName(), connectorVersion, files);
                    bundleVersion.setGitCloneUrl(repo.getHttpTransportUrl());
                    bundleVersion.setBrowseLink(repo.getHtmlUrl().toString() + "/tree/main");
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

    private String triggerJenkinsPipeline(ConnectorVersion connectorVersion, IntegrationMethod method,
                                           ConnectorBundle bundle, UploadConnectorDto dto) {
        try {
            ConnectorBundleVersion cbv = connectorVersion.getConnectorBundleVersion();
            String browseLink = cbv != null ? cbv.getBrowseLink() : "";
            String gitCloneUrl = cbv != null ? cbv.getGitCloneUrl() : "";
            String framework = bundle != null ? bundle.getFramework().name() : "";
            String className = dto.className() != null ? dto.className() : "";
            String pathToProject = dto.pathToProject() != null ? dto.pathToProject() : "";

            JenkinsClient jenkinsClient = new JenkinsClient(jenkinsProperties);
            HttpResponse<String> response = jenkinsClient.triggerJob(
                    "integration-catalog-upload-connid-connector",
                    Map.of("REPOSITORY_URL", gitCloneUrl,
                            "BRANCH_URL", browseLink,
                            "CONNECTOR_OID", method.getId().toString(),
                            "IMPL_TITLE", method.getDisplayName() != null ? method.getDisplayName() : "",
                            "IMPL_FRAMEWORK", framework,
                            "SKIP_DEPLOY", "false",
                            "CONNECTOR_CLASS", className,
                            "PATH_TO_PROJECT", pathToProject));
            log.info("Jenkins job triggered: {}", response.body());
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
        // instance: it is rewritten and flipped back to IN_REVIEW so a single record evolves.
        // REVIEWING counts as a draft too: only a superuser gets past the edit lock in that state
        // (see ApplicationService#assertNotUnderReview), and the reviewer's fixes must land on the
        // revision under review, not fork a fresh draft next to it.
        boolean editingDraft = existing.getLifecycleState() == LifecycleType.IN_REVIEW
                || existing.getLifecycleState() == LifecycleType.REJECTED
                || existing.getLifecycleState() == LifecycleType.REVIEWING;

        if (dto.minorBump() && editingDraft) {
            // "Save" on an in-review/rejected draft: a small correction. Bump in place (2.1 -> 2.2),
            // replacing the draft row so a draft keeps a single record while it is being revised.
            return rewriteWithMinorBump(existing, methodId, currentRevision, dto);
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
        return createDraft(existing, methodId, currentRevision, dto, newRevision);
    }

    /**
     * Creates a fresh in-review draft revision of a method, copying metadata, connectors and tutorial
     * files forward and leaving the source revision intact. Used both by "Save" on a published revision
     * (minor bump) and by "Save as new version" (major bump).
     */
    private String createDraft(IntegrationMethod existing, UUID methodId,
                               String currentRevision, EditIntegrationMethodDto dto, String newRevision) {
        IntegrationMethod updated = new IntegrationMethod();
        updated.setId(methodId);
        updated.setRevision(newRevision);
        updated.setApplication(existing.getApplication());
        // Inherit the source revision's creation time so the method keeps its list position.
        updated.setCreatedAt(existing.getCreatedAt());
        // A revision produced by the edit-upgrade flow always starts unpublished, pending review.
        updated.setLifecycleState(LifecycleType.IN_REVIEW);
        updated.setAuthor(existing.getAuthor());
        updated.setMaintainer(existing.getMaintainer());
        // Supported midPoint version range comes from the edit form (prefilled from the source revision).
        updated.setMidpointMinVersionId(dto.midpointMinVersion());
        updated.setMidpointMaxVersionId(dto.midpointMaxVersion());
        updated.setAppVersion(existing.getAppVersion());
        // Carry tutorial files forward into the new revision's own folder, then point file_path at it.
        String tutorialFolder = tutorialStorageService.copyTutorialFolder(methodId, currentRevision, newRevision);
        updated.setFilePath(tutorialFolder);
        updated.setIntegMethodTypes(new ArrayList<>(existing.getIntegMethodTypes()));
        updated.setDisplayName(dto.displayName());
        updated.setDescription(dto.description());
        updated.setTutorial(dto.tutorial());

        copyConnectorLinks(existing, updated);

        integrationMethodRepository.save(updated);

        saveIntegrationMethodCapabilities(dto.capabilities(), updated);

        return newRevision;
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
     * Rewrites a revision in place with a minor bump (1.1 -> 1.2): builds the bumped revision from the
     * edited data, moves the tutorial folder across, then deletes the superseded revision so only one
     * record survives. An in-review draft stays in review; a rejected revision is flipped back to
     * IN_REVIEW (resubmission), and its connectors are un-rejected too.
     */
    private String rewriteWithMinorBump(IntegrationMethod existing, UUID methodId,
                                        String currentRevision, EditIntegrationMethodDto dto) {
        String newRevision = bumpMinorRevision(currentRevision);
        // Resubmitting a rejected revision flips it back to IN_REVIEW; an in-review draft stays as-is.
        boolean wasRejected = existing.getLifecycleState() == LifecycleType.REJECTED;

        IntegrationMethod updated = new IntegrationMethod();
        updated.setId(methodId);
        updated.setRevision(newRevision);
        updated.setApplication(existing.getApplication());
        // Inherit the source revision's creation time so the method keeps its list position.
        updated.setCreatedAt(existing.getCreatedAt());
        updated.setLifecycleState(wasRejected ? LifecycleType.IN_REVIEW : existing.getLifecycleState());
        // Keep the reviewer on a revision edited during its review (REVIEWING survives the rewrite);
        // a resubmitted rejected revision starts a fresh review cycle with no reviewer.
        updated.setReviewedBy(wasRejected ? null : existing.getReviewedBy());
        updated.setAuthor(existing.getAuthor());
        updated.setMaintainer(existing.getMaintainer());
        // Supported midPoint version range comes from the edit form (prefilled from the source revision).
        updated.setMidpointMinVersionId(dto.midpointMinVersion());
        updated.setMidpointMaxVersionId(dto.midpointMaxVersion());
        updated.setAppVersion(existing.getAppVersion());
        // Move the single tutorial folder over to the bumped revision and point file_path at it.
        String tutorialFolder = tutorialStorageService.renameTutorialFolder(methodId, currentRevision, newRevision);
        updated.setFilePath(tutorialFolder);
        updated.setIntegMethodTypes(new ArrayList<>(existing.getIntegMethodTypes()));
        updated.setDisplayName(dto.displayName());
        updated.setDescription(dto.description());
        updated.setTutorial(dto.tutorial());

        copyConnectorLinks(existing, updated);

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
     * Starts a review on an in-review revision: flips IN_REVIEW -> REVIEWING. While REVIEWING the
     * revision is locked for its author (see ApplicationService#assertCanEditMethod) so no changes
     * land under the reviewer — superusers stay exempt so the reviewer can fix findings directly —
     * and only from this state do the approve/reject actions become available.
     */
    @Transactional
    public void startReviewIntegrationMethod(UUID methodId, String revision, String username) {
        IntegrationMethod draft = integrationMethodRepository.findById(new IntegrationMethodId(methodId, revision))
                .orElseThrow(() -> new RuntimeException("Integration method not found: " + methodId + "/" + revision));
        if (draft.getLifecycleState() != LifecycleType.IN_REVIEW) {
            throw new IllegalStateException("Only in-review revisions can be put under review: " + methodId + "/" + revision);
        }
        draft.setLifecycleState(LifecycleType.REVIEWING);
        draft.setReviewedBy(username);
        log.info("Started review of integration method {}/{} by {}", methodId, revision, username);
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
        draft.setLifecycleState(LifecycleType.IN_REVIEW);
        draft.setReviewedBy(null);
        log.info("Stopped review of integration method {}/{} by {}", methodId, revision, username);
    }

    /**
     * Publishes (approves) an in-review revision: activates it and then removes any other ACTIVE
     * revision of the same method that shares its major version. A minor draft therefore supersedes
     * its published baseline (e.g. activating 2.1 drops the active 2.0), while a new major leaves
     * earlier majors intact (activating 3.0 keeps 2.x). The superseded revisions and their tutorial
     * folders are deleted.
     */
    @Transactional
    public void publishIntegrationMethod(UUID methodId, String revision, String username) {
        IntegrationMethod draft = integrationMethodRepository.findById(new IntegrationMethodId(methodId, revision))
                .orElseThrow(() -> new RuntimeException("Integration method not found: " + methodId + "/" + revision));
        if (draft.getLifecycleState() != LifecycleType.IN_REVIEW
                && draft.getLifecycleState() != LifecycleType.REVIEWING) {
            throw new IllegalStateException("Only in-review revisions can be published: " + methodId + "/" + revision);
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

        // Publishing the method also makes its connectors catalog-visible: the "select connector"
        // catalog only lists connectors whose bundle is ACTIVE (and whose capabilities come from an
        // ACTIVE connector version). Newly added connectors are created IN_REVIEW, so promote each
        // linked connector's bundle, bundle versions and connector versions here. Existing catalog
        // connectors are already ACTIVE and are left untouched.
        promoteConnectorsToActive(draft);

        // Promote the parent application to ACTIVE as well. The homepage application card badge
        // reads the application's OWN lifecycle state, so a newly created app that was IN_REVIEW
        // would keep showing "In Review" even after its method is approved (the detail page reads
        // the per-method state, which is why only the homepage looked stale).
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
     * Activates the bundle, bundle versions and connector versions of every connector linked to a
     * method revision, so a published method's connectors become visible in the connector catalog.
     * Only IN_REVIEW records are promoted; already-ACTIVE ones (existing catalog connectors) are left
     * as they are.
     */
    private void promoteConnectorsToActive(IntegrationMethod method) {
        for (IntegrationMethodConnector link : method.getConnectors()) {
            Connector connector = link.getConnector();
            if (connector == null) continue;

            ConnectorBundle bundle = connector.getConnectorBundle();
            if (bundle != null) {
                if (bundle.getLifecycleState() == LifecycleType.IN_REVIEW) {
                    bundle.setLifecycleState(LifecycleType.ACTIVE);
                    connectorBundleRepository.save(bundle);
                }
                for (ConnectorBundleVersion cbv : bundle.getBundleVersions()) {
                    if (cbv.getLifecycleState() == LifecycleType.IN_REVIEW) {
                        cbv.setLifecycleState(LifecycleType.ACTIVE);
                        connectorBundleVersionRepository.save(cbv);
                    }
                }
            }
            for (ConnectorVersion cv : connector.getConnectorVersions()) {
                if (cv.getLifecycleState() == LifecycleType.IN_REVIEW) {
                    cv.setLifecycleState(LifecycleType.ACTIVE);
                    connectorVersionRepository.save(cv);
                }
            }
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
            if (connector == null) continue;

            ConnectorBundle bundle = connector.getConnectorBundle();
            if (bundle != null) {
                if (bundle.getLifecycleState() == LifecycleType.IN_REVIEW) {
                    bundle.setLifecycleState(LifecycleType.REJECTED);
                    connectorBundleRepository.save(bundle);
                }
                for (ConnectorBundleVersion cbv : bundle.getBundleVersions()) {
                    if (cbv.getLifecycleState() == LifecycleType.IN_REVIEW) {
                        cbv.setLifecycleState(LifecycleType.REJECTED);
                        connectorBundleVersionRepository.save(cbv);
                    }
                }
            }
            for (ConnectorVersion cv : connector.getConnectorVersions()) {
                if (cv.getLifecycleState() == LifecycleType.IN_REVIEW) {
                    cv.setLifecycleState(LifecycleType.REJECTED);
                    connectorVersionRepository.save(cv);
                }
            }
        }
    }

    /**
     * Undo a rejection on the connectors of a method: flip REJECTED records back to IN_REVIEW.
     * Used when a rejected revision is resubmitted (edited + saved) so its connectors are re-reviewed
     * again. ACTIVE connectors reused by the method are left untouched.
     */
    private void resetRejectedConnectorsToInReview(IntegrationMethod method) {
        for (IntegrationMethodConnector link : method.getConnectors()) {
            Connector connector = link.getConnector();
            if (connector == null) continue;

            ConnectorBundle bundle = connector.getConnectorBundle();
            if (bundle != null) {
                if (bundle.getLifecycleState() == LifecycleType.REJECTED) {
                    bundle.setLifecycleState(LifecycleType.IN_REVIEW);
                    connectorBundleRepository.save(bundle);
                }
                for (ConnectorBundleVersion cbv : bundle.getBundleVersions()) {
                    if (cbv.getLifecycleState() == LifecycleType.REJECTED) {
                        cbv.setLifecycleState(LifecycleType.IN_REVIEW);
                        connectorBundleVersionRepository.save(cbv);
                    }
                }
            }
            for (ConnectorVersion cv : connector.getConnectorVersions()) {
                if (cv.getLifecycleState() == LifecycleType.REJECTED) {
                    cv.setLifecycleState(LifecycleType.IN_REVIEW);
                    connectorVersionRepository.save(cv);
                }
            }
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
        if (draft.getLifecycleState() != LifecycleType.IN_REVIEW
                && draft.getLifecycleState() != LifecycleType.REVIEWING) {
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
        if (groups == null) return;
        for (IntegrationMethodCapabilityGroupDto group : groups) {
            if (group.objectClass() == null || group.capabilityNames() == null || group.capabilityNames().isEmpty()) continue;
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
        IntegrationMethod target = integrationMethodRepository.findById(new IntegrationMethodId(methodId, revision))
                .orElseThrow(() -> new RuntimeException("Integration method not found: " + methodId + "/" + revision));

        // A published (ACTIVE) revision is immutable: never attach a connector to it. Fork a fresh
        // in-review draft (next available major) that carries the published version forward and add the
        // connector there, leaving the published revision untouched. In-review/rejected drafts are
        // mutable, so a connector is added to them directly.
        if (target.getLifecycleState() == LifecycleType.ACTIVE) {
            target = clonePublishedAsDraft(target, methodId);
        }

        Connector connector;
        String connectorMinVersion;

        if (dto.existingConnectorId() != null) {
            connector = connectorRepository.findById(dto.existingConnectorId())
                    .orElseThrow(() -> new RuntimeException("Connector not found: " + dto.existingConnectorId()));
            connectorMinVersion = firstNonBlank(dto.connectorVersionFrom(), connector.getRevision(), "1.0.0");
        } else {
            UploadConnectorDto connDto = new UploadConnectorDto(
                    dto.displayName(), dto.framework(), dto.version(), dto.bundleName(), dto.license(),
                    dto.buildFramework(), dto.description(), dto.maintainer(), dto.browseLink(),
                    null, dto.gitCloneUrl(), dto.className(), dto.pathToProject(), dto.commitTag(),
                    dto.displayName(), null);

            ConnectorBundle bundle = createNewConnectorBundle(connDto, username);
            connectorBundleRepository.save(bundle);

            ConnectorBundleVersion bundleVersion = createBundleVersion(connDto, bundle, username);
            connectorBundleVersionRepository.save(bundleVersion);

            connector = new Connector();
            connector.setDisplayName(dto.displayName());
            connector.setRevision(dto.version() != null ? dto.version() : "1.0.0");
            connector.setAuthor(username);
            connector.setMaintainer(dto.maintainer());
            connector.setDescription(dto.description());
            connector.setFullyQualifiedClassName(dto.className());
            connector.setConnectorBundle(bundle);
            connectorRepository.save(connector);

            ConnectorVersion connectorVersion = createConnectorVersion(connDto, connector, bundleVersion, username);
            connectorVersionRepository.save(connectorVersion);

            connectorMinVersion = firstNonBlank(dto.connectorVersionFrom(), connectorVersion.getRevision(), "1.0.0");
            saveConnectorVersionCapabilities(dto.connectorCapabilities(), connectorVersion);
        }

        if (dto.midpointMinVersion() != null) target.setMidpointMinVersionId(dto.midpointMinVersion());
        if (dto.midpointMaxVersion() != null) target.setMidpointMaxVersionId(dto.midpointMaxVersion());

        // Append a new connector link, leaving any existing connectors on this integration
        // method revision untouched (a method revision may hold multiple connectors).
        IntegrationMethodConnector imc = new IntegrationMethodConnector();
        imc.setConnector(connector);
        imc.setConnectorMinVersion(connectorMinVersion);
        imc.setConnectorMaxVersion(emptyToNull(dto.connectorVersionTo()));
        imc.setIntegrationMethod(target);
        target.getConnectors().add(imc);

        integrationMethodRepository.save(target);
        return target.getRevision();
    }

    /**
     * Forks a published integration-method revision into a fresh in-review draft at the next available
     * major version, carrying its metadata, tutorial files, connector links and capabilities forward.
     * The published source revision is left untouched so it stays immutable.
     */
    private IntegrationMethod clonePublishedAsDraft(IntegrationMethod source, UUID methodId) {
        String newRevision = nextMajorRevision(methodId, source.getApplication().getId());
        IntegrationMethod draft = new IntegrationMethod();
        draft.setId(methodId);
        draft.setRevision(newRevision);
        draft.setApplication(source.getApplication());
        // Inherit the source revision's creation time so the method keeps its list position.
        draft.setCreatedAt(source.getCreatedAt());
        draft.setLifecycleState(LifecycleType.IN_REVIEW);
        draft.setAuthor(source.getAuthor());
        draft.setMaintainer(source.getMaintainer());
        draft.setMidpointMinVersionId(source.getMidpointMinVersionId());
        draft.setMidpointMaxVersionId(source.getMidpointMaxVersionId());
        draft.setAppVersion(source.getAppVersion());
        draft.setDisplayName(source.getDisplayName());
        draft.setDescription(source.getDescription());
        draft.setTutorial(source.getTutorial());
        draft.setIntegMethodTypes(new ArrayList<>(source.getIntegMethodTypes()));
        draft.setFilePath(tutorialStorageService.copyTutorialFolder(methodId, source.getRevision(), newRevision));

        copyConnectorLinks(source, draft);
        integrationMethodRepository.save(draft);
        copyCapabilities(source, draft);
        return draft;
    }

    /** Deep-copies a method's object-class capabilities (and their capability items) onto another revision. */
    private void copyCapabilities(IntegrationMethod from, IntegrationMethod to) {
        for (IntegrationMethodCapability oldCap : from.getCapabilities()) {
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
                ? firstNonBlank(link.getConnector().getRevision(), "1.0.0")
                : connectorVersionFrom.trim();
        String to = connectorVersionTo == null || connectorVersionTo.isBlank() ? null : connectorVersionTo.trim();

        link.setConnectorMinVersion(from);
        link.setConnectorMaxVersion(to);
        integrationMethodRepository.save(method);
    }

    /**
     * Deep-copies a connector and everything beneath it — its bundle, and every bundle version and
     * connector version (with their capabilities) — into brand-new rows. Used for copy-on-write when a
     * connector is shared across revisions and one revision edits it: the edit then lands on the copy and
     * leaves the shared original (e.g. a published revision) untouched. The cloned bundle keeps its name
     * but takes a fresh revision so the (bundle_name, revision) uniqueness constraint still holds.
     */
    private Connector cloneConnectorGraph(Connector src) {
        ConnectorBundle srcBundle = src.getConnectorBundle();
        ConnectorBundle bundle = new ConnectorBundle();
        bundle.setRevision(uniqueBundleRevision(srcBundle.getBundleName(), srcBundle.getRevision()));
        bundle.setAuthor(srcBundle.getAuthor());
        bundle.setMaintainer(srcBundle.getMaintainer());
        // The copy belongs to the in-review revision being edited, so it starts IN_REVIEW regardless of
        // the source's state — publishIntegrationMethod promotes it to ACTIVE (and reject marks it
        // REJECTED). This keeps the edited connector out of the catalog until the revision is approved,
        // while the shared original (e.g. the still-published connector) is untouched.
        bundle.setLifecycleState(LifecycleType.IN_REVIEW);
        bundle.setBundleName(srcBundle.getBundleName());
        bundle.setDisplayName(srcBundle.getDisplayName());
        bundle.setDescription(srcBundle.getDescription());
        bundle.setFramework(srcBundle.getFramework());
        bundle.setLicense(srcBundle.getLicense());
        bundle.setTicketingLink(srcBundle.getTicketingLink());
        bundle.setProjectHomepage(srcBundle.getProjectHomepage());
        bundle.setGitCloneUrl(srcBundle.getGitCloneUrl());
        bundle.setPathToProject(srcBundle.getPathToProject());
        bundle.setBuildFramework(srcBundle.getBuildFramework());
        connectorBundleRepository.save(bundle);

        Connector clone = new Connector();
        clone.setRevision(src.getRevision());
        clone.setAuthor(src.getAuthor());
        clone.setMaintainer(src.getMaintainer());
        clone.setDisplayName(src.getDisplayName());
        clone.setFullyQualifiedClassName(src.getFullyQualifiedClassName());
        clone.setDescription(src.getDescription());
        clone.setConnectorBundle(bundle);
        connectorRepository.save(clone);

        for (ConnectorVersion srcCv : src.getConnectorVersions()) {
            ConnectorBundleVersion srcCbv = srcCv.getConnectorBundleVersion();
            ConnectorBundleVersion cbv = null;
            if (srcCbv != null) {
                cbv = new ConnectorBundleVersion();
                cbv.setRevision(srcCbv.getRevision());
                cbv.setAuthor(srcCbv.getAuthor());
                cbv.setMaintainer(srcCbv.getMaintainer());
                cbv.setLifecycleState(LifecycleType.IN_REVIEW);
                cbv.setConnectorBundle(bundle);
                cbv.setBundleVersion(srcCbv.getBundleVersion());
                cbv.setBrowseLink(srcCbv.getBrowseLink());
                cbv.setGitCloneUrl(srcCbv.getGitCloneUrl());
                cbv.setPathToProject(srcCbv.getPathToProject());
                cbv.setBuildFramework(srcCbv.getBuildFramework());
                cbv.setCommitTag(srcCbv.getCommitTag());
                cbv.setArtifactUrl(srcCbv.getArtifactUrl());
                cbv.setErrorMessage(srcCbv.getErrorMessage());
                connectorBundleVersionRepository.save(cbv);
            }

            ConnectorVersion cv = new ConnectorVersion();
            cv.setConnector(clone);
            cv.setConnectorBundleVersion(cbv);
            cv.setRevision(srcCv.getRevision());
            cv.setAuthor(srcCv.getAuthor());
            cv.setMaintainer(srcCv.getMaintainer());
            cv.setLifecycleState(LifecycleType.IN_REVIEW);
            cv.setFullyQualifiedClassName(srcCv.getFullyQualifiedClassName());
            cv.setErrorMessage(srcCv.getErrorMessage());
            connectorVersionRepository.save(cv);

            for (ConnVersionCapability srcCap : srcCv.getCapabilities()) {
                ConnVersionCapability cap = new ConnVersionCapability();
                cap.setObjectClass(srcCap.getObjectClass());
                cap.setConnectorVersion(cv);
                ConnVersionCapability savedCap = connVersionCapabilityRepository.save(cap);
                for (ConnVersionCapabilityItem srcItem : srcCap.getItems()) {
                    ConnVersionCapabilityItem item = new ConnVersionCapabilityItem();
                    item.setConnVersionCapabilityId(savedCap.getId());
                    item.setCapabilityId(srcItem.getCapabilityId());
                    connVersionCapabilityItemRepository.save(item);
                    savedCap.getItems().add(item);
                }
                // Keep the in-memory collection in sync with what was persisted, so later reads of
                // this version's capabilities see the cloned set without a refetch.
                cv.getCapabilities().add(savedCap);
            }

            clone.getConnectorVersions().add(cv);
        }
        return clone;
    }

    /** Picks a bundle revision that keeps (bundle_name, revision) unique for a freshly cloned bundle. */
    private String uniqueBundleRevision(String bundleName, String baseRevision) {
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

    /**
     * Applies an "Edit connector" modal save. Connector- and bundle-level metadata is updated in
     * place — it is the same connector on every revision linking it. The version-level data always
     * lands as a NEW connector version (+ bundle version) row, keeping the edited one intact, so the
     * reviewer sees both the original and the edit. When the edit keeps the same build (className,
     * bundleName and commit hash unchanged) or the entered version collides with an existing one,
     * the new row's version is auto-bumped by a patch (1.1.1 -> 1.1.2) to keep versions unique and
     * its error_message records the duplicate for the reviewer — duplicates are never blocked, the
     * reviewer resolves them with the author. Only an edit changing the connector's own identity
     * (className/bundleName) of a connector shared with another revision clones the graph first
     * (see {@link #cloneConnectorGraph}) so the other revision keeps the original identity.
     */
    @Transactional
    public void updateConnector(UUID methodId, String revision, Integer connectorId, EditConnectorDto dto,
                                String username) {
        IntegrationMethod method = integrationMethodRepository.findById(new IntegrationMethodId(methodId, revision))
                .orElseThrow(() -> new RuntimeException("Integration method not found: " + methodId + "/" + revision));

        IntegrationMethodConnector link = method.getConnectors().stream()
                .filter(l -> l.getConnector() != null && connectorId.equals(l.getConnector().getId()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException(
                        "Connector " + connectorId + " is not linked to integration method " + methodId + "/" + revision));

        Connector connector = link.getConnector();

        if (changesConnectorIdentity(connector, dto)
                && integrationMethodConnectorRepository.countByConnector_Id(connectorId) > 1) {
            connector = cloneConnectorGraph(connector);
            link.setConnector(connector);
            integrationMethodRepository.save(method);
        }

        ConnectorBundle bundle = connector.getConnectorBundle();

        // The version the edit is based on: the same row the modal displayed (first with a bundle
        // version, matching the previous in-place update's selection).
        ConnectorVersion baseCv = connector.getConnectorVersions().stream()
                .filter(cv -> cv.getConnectorBundleVersion() != null)
                .findFirst()
                .orElse(null);
        ConnectorBundleVersion baseCbv = baseCv != null ? baseCv.getConnectorBundleVersion() : null;

        // Same build = className, bundleName and commit hash all unchanged by the edit (a blank
        // incoming value means "unchanged"). Such an edit duplicates the base version's content
        // (e.g. a wording fix), which the reviewer must resolve — flagged via error_message below.
        boolean sameBuild = !identifierDiffers(dto.className(),
                        firstNonBlank(baseCv != null ? baseCv.getFullyQualifiedClassName() : null,
                                connector.getFullyQualifiedClassName()))
                && !identifierDiffers(dto.bundleName(), bundle != null ? bundle.getBundleName() : null)
                && !identifierDiffers(dto.commitTag(), baseCbv != null ? baseCbv.getCommitTag() : null);

        String currentVersion = firstNonBlank(
                baseCbv != null ? baseCbv.getBundleVersion() : null,
                baseCv != null ? baseCv.getRevision() : null,
                connector.getRevision(), "1.0.0");
        String requestedVersion = (dto.version() != null && !dto.version().isBlank())
                ? dto.version().trim() : currentVersion;

        // Versions already taken on this connector/bundle; the new row must not repeat any of them.
        Set<String> takenVersions = new HashSet<>();
        connector.getConnectorVersions().forEach(v -> takenVersions.add(v.getRevision()));
        if (bundle != null) {
            for (ConnectorBundleVersion v : bundle.getBundleVersions()) {
                takenVersions.add(v.getRevision());
                takenVersions.add(v.getBundleVersion());
            }
        }

        String newVersion = requestedVersion;
        String errorMessage = null;
        if (takenVersions.contains(requestedVersion)) {
            newVersion = bumpPatchUntilFree(requestedVersion, takenVersions);
            errorMessage = "Duplicate version with (" + connector.getDisplayName() + " " + requestedVersion + ")";
        } else if (sameBuild) {
            errorMessage = "Duplicate version with (" + connector.getDisplayName() + " " + currentVersion + ")";
        }

        // Connector metadata — shared, updated in place.
        connector.setDisplayName(dto.displayName());
        connector.setMaintainer(dto.maintainer());
        connector.setDescription(dto.description());
        connector.setFullyQualifiedClassName(dto.className());
        // connector.revision mirrors the connector's current version.
        connector.setRevision(newVersion);

        // Connector bundle metadata — shared, updated in place.
        if (bundle != null) {
            bundle.setDisplayName(dto.displayName());
            bundle.setDescription(dto.description());
            bundle.setMaintainer(dto.maintainer());
            if (dto.license() != null) bundle.setLicense(dto.license());
            if (dto.bundleName() != null && !dto.bundleName().isBlank()) bundle.setBundleName(dto.bundleName());
            bundle.setTicketingLink(dto.supportPortal());
            bundle.setProjectHomepage(dto.browseLink());
            bundle.setGitCloneUrl(dto.gitCloneUrl());
            bundle.setPathToProject(dto.pathToProject());
            if (dto.buildFramework() != null) bundle.setBuildFramework(dto.buildFramework());
            connectorBundleRepository.save(bundle);
        }

        // The edit's version-level data: a fresh bundle version + connector version pair.
        ConnectorBundleVersion cbv = new ConnectorBundleVersion();
        cbv.setRevision(newVersion);
        cbv.setBundleVersion(newVersion);
        cbv.setConnectorBundle(bundle);
        cbv.setAuthor(username);
        cbv.setMaintainer(dto.maintainer());
        cbv.setLifecycleState(LifecycleType.IN_REVIEW);
        cbv.setBrowseLink(dto.browseLink());
        cbv.setGitCloneUrl(dto.gitCloneUrl());
        cbv.setPathToProject(dto.pathToProject());
        cbv.setCommitTag(dto.commitTag());
        cbv.setBuildFramework(dto.buildFramework() != null ? dto.buildFramework()
                : (baseCbv != null ? baseCbv.getBuildFramework() : null));
        connectorBundleVersionRepository.save(cbv);

        ConnectorVersion cv = new ConnectorVersion();
        cv.setConnector(connector);
        cv.setConnectorBundleVersion(cbv);
        cv.setRevision(newVersion);
        cv.setAuthor(username);
        cv.setMaintainer(dto.maintainer());
        cv.setFullyQualifiedClassName(dto.className());
        cv.setLifecycleState(LifecycleType.IN_REVIEW);
        cv.setErrorMessage(errorMessage);
        connectorVersionRepository.save(cv);
        connector.getConnectorVersions().add(cv);

        saveConnectorVersionCapabilities(dto.connectorCapabilities(), cv);

        connectorRepository.save(connector);
    }

    /**
     * Whether the edit changes the connector's own identity — className or bundleName. Version is
     * deliberately NOT part of this: a version change just adds a new version row under the same
     * connector (see {@link #updateConnector}), while a className/bundleName change makes it a
     * different connector, which must not leak onto other revisions sharing the rows. A blank
     * incoming value means "unchanged".
     */
    private boolean changesConnectorIdentity(Connector connector, EditConnectorDto dto) {
        ConnectorBundle bundle = connector.getConnectorBundle();
        return identifierDiffers(dto.className(), connector.getFullyQualifiedClassName())
                || identifierDiffers(dto.bundleName(), bundle != null ? bundle.getBundleName() : null);
    }

    private static boolean identifierDiffers(String incoming, String current) {
        if (incoming == null || incoming.isBlank()) return false;
        return !incoming.trim().equals(current == null ? "" : current.trim());
    }

    /** Patch bump: 1.1.1 -> 1.1.2; a version without a numeric patch segment gets one appended. */
    private static String bumpPatchVersion(String version) {
        String[] parts = version.split("\\.");
        if (parts.length >= 3) {
            try {
                parts[parts.length - 1] = String.valueOf(Integer.parseInt(parts[parts.length - 1]) + 1);
                return String.join(".", parts);
            } catch (NumberFormatException e) {
                // fall through — non-numeric patch segment, append instead
            }
        }
        return version + ".1";
    }

    /** Bumps the patch segment until the version is not taken (1.1.1 -> 1.1.2 -> 1.1.3 ...). */
    private static String bumpPatchUntilFree(String version, Set<String> takenVersions) {
        String candidate = bumpPatchVersion(version);
        while (takenVersions.contains(candidate)) {
            candidate = bumpPatchVersion(candidate);
        }
        return candidate;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
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

    /** Minor bump: keeps the major segment and increments the minor (1.1 -> 1.2, "1" -> "1.1"). */
    private String bumpMinorRevision(String revision) {
        return parseMajor(revision) + "." + (parseMinor(revision) + 1);
    }

    private int parseMajor(String revision) {
        if (revision == null || revision.isBlank()) return 1;
        try {
            return Integer.parseInt(revision.split("\\.")[0]);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private int parseMinor(String revision) {
        if (revision == null || revision.isBlank()) return 0;
        String[] parts = revision.split("\\.");
        if (parts.length < 2) return 0;
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void saveIntegrationMethodCapabilities(UploadImplementationDto dto, IntegrationMethod integrationMethod) {
        saveIntegrationMethodCapabilities(dto.integrationMethodCapabilities(), integrationMethod);
    }

    private void saveConnectorVersionCapabilities(UploadImplementationDto dto, ConnectorVersion connectorVersion) {
        saveConnectorVersionCapabilities(dto.connectorCapabilities(), connectorVersion);
    }

    private void saveConnectorVersionCapabilities(List<IntegrationMethodCapabilityGroupDto> groups, ConnectorVersion connectorVersion) {
        if (groups == null || groups.isEmpty()) return;

        for (IntegrationMethodCapabilityGroupDto group : groups) {
            if (group.objectClass() == null || group.capabilityNames() == null || group.capabilityNames().isEmpty()) continue;

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

    private void persistEntities(ApplicationResolution appRes, UploadResolution uploadRes,
                                  ConnectorBundleVersion bundleVersion, ConnectorVersion connectorVersion) {
        if (uploadRes.isNewVersion()) {
            if (appRes.isNew()) applicationRepository.save(appRes.application());
            if (bundleVersion.getId() == null) connectorBundleVersionRepository.save(bundleVersion);
        } else {
            applicationRepository.save(appRes.application());
            if (uploadRes.bundle().getId() == null) {
                connectorBundleRepository.save(uploadRes.bundle());
            }
            if (bundleVersion.getId() == null) connectorBundleVersionRepository.save(bundleVersion);
            connectorRepository.save(uploadRes.connector());

            IntegrationMethodConnector imc = new IntegrationMethodConnector();
            imc.setConnector(uploadRes.connector());
            imc.setConnectorMinVersion(connectorVersion.getRevision());
            imc.setIntegrationMethod(uploadRes.integrationMethod());
            uploadRes.integrationMethod().getConnectors().add(imc);

            integrationMethodRepository.save(uploadRes.integrationMethod());
        }
        connectorVersionRepository.save(connectorVersion);
    }
}
