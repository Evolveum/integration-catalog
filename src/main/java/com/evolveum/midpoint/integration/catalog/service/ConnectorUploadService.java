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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

        // An unpublished (in-review) revision is overwritten in place, so fixing a mistake in a
        // freshly saved revision doesn't spawn yet another one. Published revisions are versioned.
        if (existing.getLifecycleState() == LifecycleType.IN_REVIEW) {
            return updateIntegrationMethodInPlace(existing, methodId, currentRevision, dto);
        }

        String newRevision = dto.minorBump() ? incrementMinorRevision(currentRevision) : incrementRevision(currentRevision);

        IntegrationMethod updated = new IntegrationMethod();
        updated.setId(methodId);
        updated.setRevision(newRevision);
        updated.setApplication(existing.getApplication());
        // A revision produced by the edit-upgrade flow always starts unpublished, pending review.
        updated.setLifecycleState(LifecycleType.IN_REVIEW);
        updated.setAuthor(existing.getAuthor());
        updated.setMaintainer(existing.getMaintainer());
        updated.setMidpointMinVersionId(existing.getMidpointMinVersionId());
        updated.setMidpointMaxVersionId(existing.getMidpointMaxVersionId());
        updated.setAppVersion(existing.getAppVersion());
        // Carry tutorial files forward into the new revision's own folder, then point file_path at it.
        String tutorialFolder = tutorialStorageService.copyTutorialFolder(methodId, currentRevision, newRevision);
        updated.setFilePath(tutorialFolder);
        updated.setIntegMethodTypes(new ArrayList<>(existing.getIntegMethodTypes()));
        updated.setDisplayName(dto.displayName());
        updated.setDescription(dto.description());
        updated.setTutorial(dto.tutorial());

        for (IntegrationMethodConnector oldLink : existing.getConnectors()) {
            IntegrationMethodConnector newLink = new IntegrationMethodConnector();
            newLink.setIntegrationMethod(updated);
            newLink.setConnector(oldLink.getConnector());
            newLink.setConnectorMinVersion(oldLink.getConnectorMinVersion());
            newLink.setConnectorMaxVersion(oldLink.getConnectorMaxVersion());
            updated.getConnectors().add(newLink);
        }

        integrationMethodRepository.save(updated);

        saveIntegrationMethodCapabilities(dto.capabilities(), updated);

        return newRevision;
    }

    /**
     * Overwrites an in-review revision in place (same id + revision): updates metadata, replaces
     * its capabilities, and leaves its tutorial folder as-is so file add/delete affect only it.
     */
    private String updateIntegrationMethodInPlace(IntegrationMethod existing, UUID methodId,
                                                  String currentRevision, EditIntegrationMethodDto dto) {
        existing.setDisplayName(dto.displayName());
        existing.setDescription(dto.description());
        existing.setTutorial(dto.tutorial());
        existing.setFilePath(tutorialStorageService.folderName(methodId, currentRevision));

        // Drop the current capabilities (cascade removes their items), then recreate from the DTO.
        existing.getCapabilities().clear();
        integrationMethodRepository.saveAndFlush(existing);

        saveIntegrationMethodCapabilities(dto.capabilities(), existing);

        return currentRevision;
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
    public void addConnectorToIntegrationMethod(UUID appId, UUID methodId, String revision,
                                                AddConnectorDto dto, String username) {
        IntegrationMethod method = integrationMethodRepository.findById(new IntegrationMethodId(methodId, revision))
                .orElseThrow(() -> new RuntimeException("Integration method not found: " + methodId + "/" + revision));

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

        if (dto.midpointMinVersion() != null) method.setMidpointMinVersionId(dto.midpointMinVersion());
        if (dto.midpointMaxVersion() != null) method.setMidpointMaxVersionId(dto.midpointMaxVersion());

        // Append a new connector link, leaving any existing connectors on this integration
        // method revision untouched (a method revision may hold multiple connectors).
        IntegrationMethodConnector imc = new IntegrationMethodConnector();
        imc.setConnector(connector);
        imc.setConnectorMinVersion(connectorMinVersion);
        imc.setConnectorMaxVersion(emptyToNull(dto.connectorVersionTo()));
        imc.setIntegrationMethod(method);
        method.getConnectors().add(imc);

        integrationMethodRepository.save(method);
    }

    /**
     * Updates an existing connector (and its bundle / latest version) in place, replacing the
     * fields edited via the "Edit connector" modal. The connector must be linked to the given
     * integration method revision.
     */
    @Transactional
    public void updateConnector(UUID methodId, String revision, Integer connectorId, EditConnectorDto dto) {
        IntegrationMethod method = integrationMethodRepository.findById(new IntegrationMethodId(methodId, revision))
                .orElseThrow(() -> new RuntimeException("Integration method not found: " + methodId + "/" + revision));

        Connector connector = method.getConnectors().stream()
                .map(IntegrationMethodConnector::getConnector)
                .filter(Objects::nonNull)
                .filter(c -> connectorId.equals(c.getId()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException(
                        "Connector " + connectorId + " is not linked to integration method " + methodId + "/" + revision));

        // Connector
        connector.setDisplayName(dto.displayName());
        connector.setMaintainer(dto.maintainer());
        connector.setDescription(dto.description());
        connector.setFullyQualifiedClassName(dto.className());

        // Connector bundle
        ConnectorBundle bundle = connector.getConnectorBundle();
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

        // Latest connector version + its bundle version
        Optional<ConnectorVersion> latestCv = connector.getConnectorVersions().stream()
                .filter(cv -> cv.getConnectorBundleVersion() != null)
                .findFirst();
        if (latestCv.isPresent()) {
            ConnectorVersion cv = latestCv.get();
            cv.setMaintainer(dto.maintainer());
            cv.setFullyQualifiedClassName(dto.className());

            ConnectorBundleVersion cbv = cv.getConnectorBundleVersion();
            cbv.setBrowseLink(dto.browseLink());
            cbv.setGitCloneUrl(dto.gitCloneUrl());
            cbv.setPathToProject(dto.pathToProject());
            cbv.setCommitTag(dto.commitTag());
            if (dto.buildFramework() != null) cbv.setBuildFramework(dto.buildFramework());
            connectorBundleVersionRepository.save(cbv);
            connectorVersionRepository.save(cv);

            replaceConnectorVersionCapabilities(cv, dto.connectorCapabilities());
        }

        connectorRepository.save(connector);
    }

    private void replaceConnectorVersionCapabilities(ConnectorVersion connectorVersion,
                                                     List<IntegrationMethodCapabilityGroupDto> groups) {
        // Remove existing capabilities (items cascade away via orphanRemoval)
        if (connectorVersion.getCapabilities() != null && !connectorVersion.getCapabilities().isEmpty()) {
            connVersionCapabilityRepository.deleteAll(connectorVersion.getCapabilities());
            connectorVersion.getCapabilities().clear();
        }
        saveConnectorVersionCapabilities(groups, connectorVersion);
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

    private String incrementRevision(String revision) {
        if (revision == null || revision.isBlank()) return "1";
        String[] parts = revision.split("\\.");
        try {
            int last = Integer.parseInt(parts[parts.length - 1]);
            parts[parts.length - 1] = String.valueOf(last + 1);
        } catch (NumberFormatException e) {
            return revision + ".1";
        }
        return String.join(".", parts);
    }

    private String incrementMinorRevision(String revision) {
        if (revision == null || revision.isBlank()) return "1.0";
        String[] parts = revision.split("\\.");
        if (parts.length < 2) return revision + ".1.0";
        try {
            int minor = Integer.parseInt(parts[parts.length - 2]);
            parts[parts.length - 2] = String.valueOf(minor + 1);
            parts[parts.length - 1] = "0";
        } catch (NumberFormatException e) {
            return revision + ".0";
        }
        return String.join(".", parts);
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
