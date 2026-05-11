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
import com.evolveum.midpoint.integration.catalog.dto.ImplementationDTO;
import com.evolveum.midpoint.integration.catalog.dto.UploadImplementationDto;
import com.evolveum.midpoint.integration.catalog.integration.GithubClient;
import com.evolveum.midpoint.integration.catalog.integration.JenkinsClient;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.evolveum.midpoint.integration.catalog.repository.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorUploadService {

    private final ApplicationRepository applicationRepository;
    private final IntegrationMethodRepository integrationMethodRepository;
    private final ConnectorRepository connectorRepository;
    private final ConnectorBundleRepository connectorBundleRepository;
    private final ConnectorBundleVersionRepository connectorBundleVersionRepository;
    private final GithubProperties githubProperties;
    private final JenkinsProperties jenkinsProperties;
    private final ApplicationTagService applicationTagService;

    private record ApplicationResolution(Application application, boolean isNew,
                                          List<String> originNames, List<ApplicationTagDto> tagDtos) {}

    private record UploadResolution(IntegrationMethod integrationMethod, Connector connector,
                                     ConnectorBundle bundle, boolean isNewVersion) {}

    @Transactional
    public String uploadConnector(UploadImplementationDto dto, String username) {
        ApplicationResolution appRes = resolveApplication(dto);
        UploadResolution uploadRes = resolveUpload(dto, appRes.application());
        ConnectorBundleVersion bundleVersion = createBundleVersion(dto.implementation(), uploadRes.bundle());
        ConnectorVersion connectorVersion = createConnectorVersion(
                dto.implementation(), uploadRes.connector(), bundleVersion, username);

        applicationTagService.processOrigins(appRes.application(), appRes.originNames(), appRes.isNew());
        applicationTagService.processTags(appRes.application(), appRes.tagDtos(), appRes.isNew());

        setUpRelationships(uploadRes, bundleVersion);
        setDefaults(appRes.application(), uploadRes.bundle(), bundleVersion, connectorVersion);
        copyFromLatestVersionIfNeeded(uploadRes, bundleVersion, connectorVersion);
        createGitHubRepositoryIfNeeded(uploadRes, bundleVersion, connectorVersion, dto.files());

        persistEntities(appRes, uploadRes, bundleVersion, connectorVersion);
        triggerJenkinsPipeline(connectorVersion, uploadRes.integrationMethod(), uploadRes.bundle(), dto.implementation());

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

    private UploadResolution resolveUpload(UploadImplementationDto dto, Application application) {
        ImplementationDTO implDto = dto.implementation();
        boolean isNewVersion = false;

        IntegrationMethod integrationMethod;
        Connector connector;
        ConnectorBundle bundle;

        if (implDto.implementationId() != null) {
            // Adding a new version to an existing integration method
            integrationMethod = integrationMethodRepository.findByApplicationId(application.getId()).stream()
                    .filter(m -> m.getId().equals(implDto.implementationId()))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Integration method not found: " + implDto.implementationId()));
            isNewVersion = true;
            // Reuse existing connector link
            connector = integrationMethod.getConnectors().isEmpty() ? null
                    : integrationMethod.getConnectors().get(0).getConnector();
            bundle = connector != null ? connector.getConnectorBundle() : createNewConnectorBundle(implDto);
        } else {
            // Entirely new integration method
            integrationMethod = new IntegrationMethod();
            integrationMethod.setDisplayName(implDto.displayName());
            integrationMethod.setDescription(implDto.description());
            integrationMethod.setApplication(application);
            integrationMethod.setLifecycleState(LifecycleType.IN_REVIEW);

            bundle = createNewConnectorBundle(implDto);
            connector = new Connector();
            connector.setDisplayName(implDto.displayName());
            connector.setFullyQualifiedClassName(implDto.className());
            connector.setConnectorBundle(bundle);
        }

        return new UploadResolution(integrationMethod, connector, bundle, isNewVersion);
    }

    private ConnectorBundle createNewConnectorBundle(ImplementationDTO dto) {
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
        bundle.setFramework(framework);
        bundle.setLicense(dto.license() != null ? dto.license() : ConnectorBundle.LicenseType.APACHE_2);
        bundle.setBundleName(dto.bundleName());
        bundle.setMaintainer(dto.maintainer());
        bundle.setTicketingLink(dto.ticketingSystemLink());
        bundle.setLifecycleState(LifecycleType.IN_REVIEW);
        return bundle;
    }

    private ConnectorBundleVersion createBundleVersion(ImplementationDTO dto, ConnectorBundle bundle) {
        ConnectorBundleVersion cbv = new ConnectorBundleVersion();
        cbv.setBundleVersion(dto.connectorVersion());
        cbv.setConnectorBundle(bundle);
        cbv.setBuildFramework(dto.buildFramework() != null ? dto.buildFramework() : BuildFrameworkType.MAVEN);
        cbv.setPathToProject(dto.pathToProject());
        cbv.setBrowseLink(dto.browseLink());
        cbv.setGitCloneUrl(dto.gitCloneUrl());
        cbv.setLifecycleState(LifecycleType.IN_REVIEW);
        return cbv;
    }

    private ConnectorVersion createConnectorVersion(ImplementationDTO dto, Connector connector,
                                                     ConnectorBundleVersion bundleVersion, String username) {
        ConnectorVersion cv = new ConnectorVersion();
        cv.setConnector(connector);
        cv.setConnectorBundleVersion(bundleVersion);
        cv.setFullyQualifiedClassName(dto.className());
        cv.setAuthor(username);
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
        if (bundle.getBundleName() == null) {
            bundle.setBundleName("connector-bundle");
        }
        if (bundleVersion.getBuildFramework() == null) {
            bundleVersion.setBuildFramework(BuildFrameworkType.MAVEN);
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
                                           ConnectorBundle bundle, ImplementationDTO dto) {
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

    private void persistEntities(ApplicationResolution appRes, UploadResolution uploadRes,
                                  ConnectorBundleVersion bundleVersion, ConnectorVersion connectorVersion) {
        if (uploadRes.isNewVersion()) {
            if (appRes.isNew()) applicationRepository.save(appRes.application());
            connectorBundleVersionRepository.save(bundleVersion);
        } else {
            applicationRepository.save(appRes.application());
            connectorBundleRepository.save(uploadRes.bundle());
            connectorBundleVersionRepository.save(bundleVersion);
            connectorRepository.save(uploadRes.connector());
            integrationMethodRepository.save(uploadRes.integrationMethod());
        }
    }
}
