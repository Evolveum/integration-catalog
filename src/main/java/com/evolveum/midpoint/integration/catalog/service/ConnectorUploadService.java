/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.common.ItemFile;
import com.evolveum.midpoint.integration.catalog.configuration.GithubProperties;
import com.evolveum.midpoint.integration.catalog.configuration.JenkinsProperties;
import com.evolveum.midpoint.integration.catalog.dto.ApplicationTagDto;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            // New integration method reusing an existing connector bundle from the catalog
            bundle = connectorBundleRepository.findById(connDto.connectorBundleId())
                    .orElseThrow(() -> new RuntimeException("Connector bundle not found: " + connDto.connectorBundleId()));
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
            boolean hasLinks = bundleVersion.getBrowseLink() != null && !bundleVersion.getBrowseLink().isEmpty();
            if (!hasLinks) {
                try {
                    GithubClient githubClient = new GithubClient(githubProperties);
                    GHRepository repo = githubClient.createProjectForConnectorVersion(
                            res.integrationMethod().getDisplayName(), connectorVersion, files);
                    bundleVersion.setGitCloneUrl(repo.getHttpTransportUrl());
                    bundleVersion.setBrowseLink(repo.getHtmlUrl().toString() + "/tree/main");
                } catch (Exception e) {
                    log.error("Failed to create GitHub repository: {}", e.getMessage());
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

        String newRevision = dto.minorBump() ? incrementMinorRevision(currentRevision) : incrementRevision(currentRevision);

        IntegrationMethod updated = new IntegrationMethod();
        updated.setId(methodId);
        updated.setRevision(newRevision);
        updated.setApplication(existing.getApplication());
        updated.setLifecycleState(existing.getLifecycleState());
        updated.setAuthor(existing.getAuthor());
        updated.setMaintainer(existing.getMaintainer());
        updated.setMidpointMinVersionId(existing.getMidpointMinVersionId());
        updated.setMidpointMaxVersionId(existing.getMidpointMaxVersionId());
        updated.setAppVersion(existing.getAppVersion());
        updated.setFilePath(dto.removeFile() ? null : existing.getFilePath());
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

        if (dto.capabilities() != null) {
            for (IntegrationMethodCapabilityGroupDto group : dto.capabilities()) {
                if (group.objectClass() == null || group.capabilityNames() == null || group.capabilityNames().isEmpty()) continue;
                IntegrationMethodCapability cap = new IntegrationMethodCapability();
                cap.setObjectClass(group.objectClass());
                cap.setIntegrationMethod(updated);
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

        return newRevision;
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
        List<IntegrationMethodCapabilityGroupDto> groups = dto.integrationMethodCapabilities();
        if (groups == null || groups.isEmpty()) return;

        for (IntegrationMethodCapabilityGroupDto group : groups) {
            if (group.objectClass() == null || group.capabilityNames() == null || group.capabilityNames().isEmpty()) continue;

            IntegrationMethodCapability cap = new IntegrationMethodCapability();
            cap.setObjectClass(group.objectClass());
            cap.setIntegrationMethod(integrationMethod);
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

    private void saveConnectorVersionCapabilities(UploadImplementationDto dto, ConnectorVersion connectorVersion) {
        List<IntegrationMethodCapabilityGroupDto> groups = dto.connectorCapabilities();
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
