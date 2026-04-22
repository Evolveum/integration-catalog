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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Service for handling connector uploads to the integration catalog.
 * Manages the creation of applications, implementations, and versions,
 * and triggers Jenkins pipelines for building.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorUploadService {

    private final ApplicationRepository applicationRepository;
    private final ImplementationRepository implementationRepository;
    private final ImplementationVersionRepository implementationVersionRepository;
    private final BundleVersionRepository bundleVersionRepository;
    private final ConnectorBundleRepository connectorBundleRepository;
    private final GithubProperties githubProperties;
    private final JenkinsProperties jenkinsProperties;
    private final ApplicationTagService applicationTagService;

    // ==================== Helper Records ====================

    /**
     * Result of resolving the application for upload.
     */
    private record ApplicationResolution(Application application, boolean isNew, List<String> originNames, List<ApplicationTagDto> tagDtos) {}

    /**
     * Result of resolving the implementation and bundle for upload.
     */
    private record ImplementationResolution(Implementation implementation, ConnectorBundle bundle, boolean isNewVersion) {}

    // ==================== Main Upload Method ====================

    /**
     * Uploads a connector to the integration catalog.
     * Creates or updates application, implementation, and version entities,
     * then triggers a Jenkins pipeline for building.
     *
     * @param dto UploadImplementationDto containing application, implementation, and files
     * @param username the username of the uploader
     * @return Jenkins response or error message
     */
    @Transactional
    public String uploadConnector(UploadImplementationDto dto, String username) {
        // 1. Resolve entities
        ApplicationResolution appRes = resolveApplication(dto);
        ImplementationResolution implRes = resolveImplementationAndBundle(dto, appRes.application());
        BundleVersion bundleVersion = createBundleVersion(dto.implementation(), implRes.bundle());
        ImplementationVersion implVersion = createImplementationVersion(
                dto.implementation(), implRes.implementation(), bundleVersion, username);

        // 2. Process metadata
        applicationTagService.processOrigins(appRes.application(), appRes.originNames(), appRes.isNew());
        applicationTagService.processTags(appRes.application(), appRes.tagDtos(), appRes.isNew());

        // 3. Set up relationships and defaults
        setUpRelationships(implRes, bundleVersion);
        setDefaults(appRes.application(), implRes.bundle(), bundleVersion, implVersion, implRes.implementation());
        copyFromLatestVersionIfNeeded(implRes, bundleVersion, implVersion);

        // 4. Handle external integrations
        createGitHubRepositoryIfNeeded(implRes, bundleVersion, implVersion, dto.files());

        // 5. Persist and trigger pipeline
        persistEntities(appRes, implRes, bundleVersion, implVersion);
        triggerJenkinsPipeline(implVersion, implRes.implementation(), dto.implementation());
        return appRes.application().getId() + "|" + implVersion.getId();
    }

    // ==================== Resolution Methods ====================

    /**
     * Resolves or creates the application for the upload.
     */
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

    /**
     * Resolves or creates the implementation and connector bundle.
     */
    private ImplementationResolution resolveImplementationAndBundle(UploadImplementationDto dto, Application application) {
        ImplementationDTO implementationDto = dto.implementation();
        Implementation implementation = new Implementation();
        implementation.setDisplayName(implementationDto.displayName());
        implementation.setApplication(application);

        ConnectorBundle connectorBundle;
        boolean isNewVersion = false;

        if (implementationDto.implementationId() != null) {
            Implementation existingImpl = implementationRepository.findById(implementationDto.implementationId())
                    .orElseThrow(() -> new RuntimeException("Implementation not found with id: " + implementationDto.implementationId()));
            isNewVersion = true;
            implementation = existingImpl;
            connectorBundle = existingImpl.getConnectorBundle();
        } else {
            connectorBundle = createNewConnectorBundle(implementationDto);
        }

        return new ImplementationResolution(implementation, connectorBundle, isNewVersion);
    }

    // ==================== Factory Methods ====================

    /**
     * Creates a new connector bundle from the DTO data.
     */
    private ConnectorBundle createNewConnectorBundle(ImplementationDTO implementationDto) {
        ConnectorBundle.FrameworkType framework = implementationDto.framework();
        if (framework == null && implementationDto.buildFramework() != null) {
            framework = (implementationDto.buildFramework() == BundleVersion.BuildFrameworkType.MAVEN)
                    ? ConnectorBundle.FrameworkType.JAVA_BASED
                    : ConnectorBundle.FrameworkType.LOW_CODE;
        }

        if (framework == null) {
            throw new IllegalArgumentException("Framework must be specified or buildFramework must be provided");
        }

        ConnectorBundle bundle = new ConnectorBundle();
        bundle.setFramework(framework);
        bundle.setLicense(implementationDto.license());
        bundle.setBundleName(implementationDto.bundleName());
        bundle.setMaintainer(implementationDto.maintainer());
        bundle.setTicketingSystemLink(implementationDto.ticketingSystemLink());
        return bundle;
    }

    /**
     * Creates and initializes a new BundleVersion.
     */
    private BundleVersion createBundleVersion(ImplementationDTO implementationDto, ConnectorBundle connectorBundle) {
        BundleVersion bundleVersion = new BundleVersion();
        bundleVersion.setConnectorVersion(implementationDto.connectorVersion());
        bundleVersion.setConnectorBundle(connectorBundle);
        bundleVersion.setBuildFramework(implementationDto.buildFramework());
        bundleVersion.setPathToProject(implementationDto.pathToProject());
        bundleVersion.setBrowseLink(implementationDto.browseLink());
        bundleVersion.setCheckoutLink(implementationDto.checkoutLink());
        return bundleVersion;
    }

    /**
     * Creates and initializes a new ImplementationVersion.
     */
    private ImplementationVersion createImplementationVersion(ImplementationDTO implementationDto,
                                                               Implementation implementation,
                                                               BundleVersion bundleVersion,
                                                               String username) {
        ImplementationVersion implVersion = new ImplementationVersion();
        implVersion.setDescription(implementationDto.description());
        implVersion.setImplementation(implementation);
        implVersion.setBundleVersion(bundleVersion);
        implVersion.setAuthor(username);
        implVersion.setErrorMessage(null);
        return implVersion;
    }

    // ==================== Setup Methods ====================

    /**
     * Sets up relationships between entities for new implementations.
     */
    private void setUpRelationships(ImplementationResolution implRes, BundleVersion bundleVersion) {
        if (!implRes.isNewVersion()) {
            implRes.implementation().setConnectorBundle(implRes.bundle());
        }
        bundleVersion.setConnectorBundle(implRes.bundle());
    }

    /**
     * Sets default values for all entities.
     */
    private void setDefaults(Application application, ConnectorBundle bundle, BundleVersion bundleVersion,
                             ImplementationVersion implVersion, Implementation implementation) {
        // Application defaults
        if (application.getLifecycleState() == null) {
            application.setLifecycleState(Application.ApplicationLifecycleType.IN_REVIEW);
        }

        // ImplementationVersion defaults
        if (implVersion.getLifecycleState() == null) {
            implVersion.setLifecycleState(ImplementationVersion.ImplementationVersionLifecycleType.IN_REVIEW);
        }
        if (implVersion.getPublishDate() == null) {
            implVersion.setPublishDate(OffsetDateTime.now());
        }

        // ConnectorBundle defaults
        if (bundle.getBundleName() == null) {
            String bundleName = implementation.getDisplayName() != null
                    ? implementation.getDisplayName().toLowerCase().replaceAll("[^a-z0-9]", "-")
                    : "connector-bundle";
            bundle.setBundleName(bundleName);
        }
        if (bundle.getLicense() == null) {
            bundle.setLicense(ConnectorBundle.LicenseType.APACHE_2);
        }

        // BundleVersion defaults
        if (bundleVersion.getReleasedDate() == null) {
            bundleVersion.setReleasedDate(java.time.LocalDate.now());
        }
        if (bundleVersion.getBuildFramework() == null) {
            bundleVersion.setBuildFramework(BundleVersion.BuildFrameworkType.MAVEN);
        }
    }

    /**
     * Copies data from the latest version when adding a new version to existing implementation.
     */
    private void copyFromLatestVersionIfNeeded(ImplementationResolution implRes, BundleVersion bundleVersion,
                                                ImplementationVersion implVersion) {
        if (!implRes.isNewVersion()) {
            return;
        }

        Implementation existingImpl = implRes.implementation();
        if (existingImpl.getImplementationVersions() == null || existingImpl.getImplementationVersions().isEmpty()) {
            return;
        }

        ImplementationVersion latestVersion = existingImpl.getImplementationVersions().stream()
                .max(ImplementationVersion.latestByPublishDate)
                .orElse(null);

        if (latestVersion == null) {
            return;
        }

        if (implVersion.getCapabilities() == null && latestVersion.getCapabilities() != null) {
            implVersion.setCapabilities(latestVersion.getCapabilities());
        }
        if (implVersion.getClassName() == null && latestVersion.getClassName() != null) {
            implVersion.setClassName(latestVersion.getClassName());
        }

        BundleVersion latestBundleVersion = latestVersion.getBundleVersion();
        if (latestBundleVersion != null) {
            if (bundleVersion.getBrowseLink() == null || bundleVersion.getBrowseLink().isEmpty()) {
                bundleVersion.setBrowseLink(latestBundleVersion.getBrowseLink());
            }
            if (bundleVersion.getCheckoutLink() == null || bundleVersion.getCheckoutLink().isEmpty()) {
                bundleVersion.setCheckoutLink(latestBundleVersion.getCheckoutLink());
            }
            if (bundleVersion.getDownloadLink() == null || bundleVersion.getDownloadLink().isEmpty()) {
                bundleVersion.setDownloadLink(latestBundleVersion.getDownloadLink());
            }
        }
    }

    // ==================== External Integration Methods ====================

    /**
     * Creates a GitHub repository for SCIM_REST implementations if needed.
     */
    private void createGitHubRepositoryIfNeeded(ImplementationResolution implRes, BundleVersion bundleVersion,
                                                 ImplementationVersion implVersion, List<ItemFile> files) {
        if (implRes.isNewVersion()) {
            return;
        }

        ConnectorBundle bundle = implRes.bundle();
        Implementation implementation = implRes.implementation();

        if (ConnectorBundle.FrameworkType.LOW_CODE.equals(bundle.getFramework())) {
            boolean hasLinks = bundleVersion.getCheckoutLink() != null && !bundleVersion.getCheckoutLink().isEmpty()
                    && bundleVersion.getBrowseLink() != null && !bundleVersion.getBrowseLink().isEmpty();

            if (!hasLinks) {
                try {
                    GithubClient githubClient = new GithubClient(githubProperties);
                    GHRepository repository = githubClient.createProject(
                            implementation.getDisplayName(), implVersion, files);
                    bundleVersion.setCheckoutLink(repository.getHttpTransportUrl());
                    bundleVersion.setBrowseLink(repository.getHtmlUrl().toString() + "/tree/main");
                } catch (Exception e) {
                    implVersion.setErrorMessage(e.getMessage());
                    log.error("Failed to create GitHub repository: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Triggers the Jenkins pipeline for building the connector.
     */
    private String triggerJenkinsPipeline(ImplementationVersion implVersion, Implementation implementation,
                                           ImplementationDTO implementationDto) {
        String errorMessage = implVersion.getErrorMessage();
        if (errorMessage != null) {
            return errorMessage;
        }

        try {
            BundleVersion bundleVersion = implVersion.getBundleVersion();
            String checkoutLink = bundleVersion != null ? bundleVersion.getCheckoutLink() : "";
            String browseLink = bundleVersion != null ? bundleVersion.getBrowseLink() : "";
            String framework = implementation.getConnectorBundle() != null
                    ? implementation.getConnectorBundle().getFramework().name() : "";
            String className = implementationDto.className() != null ? implementationDto.className() : "";
            String pathToProject = implementationDto.pathToProject() != null ? implementationDto.pathToProject() : "";

            JenkinsClient jenkinsClient = new JenkinsClient(jenkinsProperties);
            HttpResponse<String> response = jenkinsClient.triggerJob(
                    "integration-catalog-upload-connid-connector",
                    Map.of("REPOSITORY_URL", checkoutLink,
                            "BRANCH_URL", browseLink,
                            "CONNECTOR_OID", implVersion.getId().toString(),
                            "IMPL_TITLE", implementation.getDisplayName(),
                            "IMPL_FRAMEWORK", framework,
                            "SKIP_DEPLOY", "false",
                            "CONNECTOR_CLASS", className,
                            "PATH_TO_PROJECT", pathToProject));
            log.info("Jenkins job triggered: {}", response.body());
            return response.body();
        } catch (Exception e) {
            implVersion.setErrorMessage(e.getMessage());
            implementationVersionRepository.save(implVersion);
            log.error("Failed to trigger Jenkins pipeline: {}", e.getMessage());
            return e.getMessage();
        }
    }

    // ==================== Persistence ====================

    /**
     * Persists all entities in the correct order.
     */
    private void persistEntities(ApplicationResolution appRes, ImplementationResolution implRes,
                                  BundleVersion bundleVersion, ImplementationVersion implVersion) {
        if (implRes.isNewVersion()) {
            bundleVersionRepository.save(bundleVersion);
            implementationVersionRepository.save(implVersion);
        } else {
            applicationRepository.save(appRes.application());
            connectorBundleRepository.save(implRes.bundle());
            bundleVersionRepository.save(bundleVersion);
            implementationRepository.save(implRes.implementation());
            implementationVersionRepository.save(implVersion);
        }
    }
}
