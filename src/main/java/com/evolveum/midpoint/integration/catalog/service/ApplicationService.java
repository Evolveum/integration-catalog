/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.*;
import com.evolveum.midpoint.integration.catalog.common.ItemFile;
import com.evolveum.midpoint.integration.catalog.mapper.ApplicationMapper;
import com.evolveum.midpoint.integration.catalog.integration.GithubClient;
import com.evolveum.midpoint.integration.catalog.integration.JenkinsClient;
import com.evolveum.midpoint.integration.catalog.configuration.GithubProperties;
import com.evolveum.midpoint.integration.catalog.configuration.JenkinsProperties;
import com.evolveum.midpoint.integration.catalog.form.ContinueForm;
import com.evolveum.midpoint.integration.catalog.form.FailForm;
import com.evolveum.midpoint.integration.catalog.form.SearchForm;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.evolveum.midpoint.integration.catalog.repository.*;
import com.evolveum.midpoint.integration.catalog.repository.adapter.ApplicationReadPort;
import com.evolveum.midpoint.integration.catalog.configuration.LogoStorageProperties;

import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Created by Dominik.
 */
@Slf4j
@Service
public class ApplicationService {

    private static final long DOWNLOAD_OFFSET_SECONDS = 10;

    @Autowired
    private final ApplicationRepository applicationRepository;

    @Autowired
    private final ApplicationTagRepository applicationTagRepository;

    @Autowired
    private final CountryOfOriginRepository countryOfOriginRepository;

    @Autowired
    private final ImplementationRepository implementationRepository;

    @Autowired
    private final ImplementationVersionRepository implementationVersionRepository;

    @Autowired
    private final ConnidVersionRepository connidVersionRepository;

    @Autowired
    private final GithubProperties githubProperties;

    @Autowired
    private final JenkinsProperties jenkinsProperties;

    @Autowired
    private final DownloadRepository downloadRepository;

    @Autowired
    private final RequestRepository requestRepository;

    @Autowired
    private final VoteRepository voteRepository;

    @Autowired
    private final ApplicationReadPort applicationReadPort;

    @Autowired
    private final ApplicationMapper applicationMapper;

    @Autowired
    private final BundleVersionRepository bundleVersionRepository;

    @Autowired
    private final ConnectorBundleRepository connectorBundleRepository;

    @Autowired
    private final ApplicationApplicationTagRepository applicationApplicationTagRepository;

    @Autowired
    private final LogoStorageProperties logoStorageProperties;

    public ApplicationService(ApplicationRepository applicationRepository,
                              ApplicationTagRepository applicationTagRepository,
                              CountryOfOriginRepository countryOfOriginRepository,
                              ImplementationRepository implementationRepository,
                              ImplementationVersionRepository implementationVersionRepository,
                              ConnectorBundleRepository connectorBundleRepository,
                              BundleVersionRepository bundleVersionRepository,
                              ConnidVersionRepository connidVersionRepository,
                              GithubProperties githubProperties,
                              JenkinsProperties jenkinsProperties,
                              DownloadRepository downloadRepository,
                              RequestRepository requestRepository,
                              VoteRepository voteRepository,
                              ApplicationReadPort applicationReadPort,
                              ApplicationMapper applicationMapper,
                              ApplicationApplicationTagRepository applicationApplicationTagRepository,
                              LogoStorageProperties logoStorageProperties
    ) {
        this.applicationRepository = applicationRepository;
        this.applicationTagRepository = applicationTagRepository;
        this.countryOfOriginRepository = countryOfOriginRepository;
        this.implementationRepository = implementationRepository;
        this.implementationVersionRepository = implementationVersionRepository;
        this.connidVersionRepository = connidVersionRepository;
        this.githubProperties = githubProperties;
        this.jenkinsProperties = jenkinsProperties;
        this.downloadRepository = downloadRepository;
        this.requestRepository = requestRepository;
        this.voteRepository = voteRepository;
        this.applicationReadPort = applicationReadPort;
        this.applicationMapper = applicationMapper;
        this.bundleVersionRepository = bundleVersionRepository;
        this.connectorBundleRepository = connectorBundleRepository;
        this.applicationApplicationTagRepository = applicationApplicationTagRepository;
        this.logoStorageProperties = logoStorageProperties;
    }

    public Application getApplication(UUID uuid) {
        return applicationRepository.findById(uuid)
                .orElseThrow(() -> new RuntimeException("Application not found with id: " + uuid));
    }

    public ImplementationVersion getImplementationVersion(UUID uuid) {
        return implementationVersionRepository.getReferenceById(uuid);
    }

    public ConnidVersion getConnectorVersion(UUID id) {
        ImplementationVersion implVersion = this.implementationVersionRepository.getReferenceById(id);
        if (implVersion.getBundleVersion() != null && implVersion.getBundleVersion().getConnidVersion() != null) {
            return connidVersionRepository.getReferenceById(implVersion.getBundleVersion().getConnidVersion());
        }
        return null;
    }

    public List<ApplicationTag> getApplicationTags() {
        return applicationTagRepository.findAll();
    }

    public List<CategoryCountDto> getCategoryCounts() {
        List<ApplicationTag> categoryTags = applicationTagRepository.findByTagType(ApplicationTag.ApplicationTagType.CATEGORY);

        List<CategoryCountDto> categoryCounts = categoryTags.stream()
                .map(tag -> new CategoryCountDto(
                        tag.getDisplayName(),
                        (long) tag.getApplicationApplicationTags().size()
                ))
                .toList();

        return categoryCounts;
    }

    public List<CountryOfOrigin> getCountriesOfOrigin() {
        return countryOfOriginRepository.findAll();
    }

    /**
     * Check if a connector version already exists
     * @param connectorVersion The version string to check
     * @return true if the version already exists, false otherwise
     */
    public boolean checkVersionExists(String connectorVersion) {
        if (connectorVersion == null || connectorVersion.isEmpty()) {
            return false;
        }
        return bundleVersionRepository.existsByConnectorVersion(connectorVersion);
    }

    // ==================== Upload Connector Helper Records ====================

    /**
     * Result of resolving the application for upload.
     */
    private record ApplicationResolution(Application application, boolean isNew, List<String> originNames, List<ApplicationTagDto> tagDtos) {}

    /**
     * Result of resolving the implementation and bundle for upload.
     */
    private record ImplementationResolution(Implementation implementation, ConnectorBundle bundle, boolean isNewVersion) {}

    // ==================== Upload Connector Helper Methods ====================

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

    /**
     * Creates a new connector bundle from the DTO data.
     */
    private ConnectorBundle createNewConnectorBundle(ImplementationDTO implementationDto) {
        ConnectorBundle.FrameworkType framework = implementationDto.framework();
        if (framework == null && implementationDto.buildFramework() != null) {
            framework = (implementationDto.buildFramework() == BundleVersion.BuildFrameworkType.MAVEN)
                    ? ConnectorBundle.FrameworkType.CONNID
                    : ConnectorBundle.FrameworkType.SCIM_REST;
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

    /**
     * Processes origin countries for the application.
     */
    private void processOrigins(Application application, List<String> originNames, boolean isNewApplication) {
        if (originNames == null || originNames.isEmpty()) {
            return;
        }

        if (application.getApplicationOrigins() == null) {
            application.setApplicationOrigins(new java.util.HashSet<>());
        } else if (isNewApplication) {
            application.getApplicationOrigins().clear();
        }

        for (String countryDisplayName : originNames) {
            if (countryDisplayName == null || countryDisplayName.trim().isEmpty()) {
                continue;
            }

            String normalizedName = countryDisplayName.toLowerCase().replaceAll("\\s+", "_");
            CountryOfOrigin country = findOrCreateCountry(normalizedName, countryDisplayName);

            if (!hasOrigin(application, country)) {
                ApplicationOrigin newOrigin = new ApplicationOrigin();
                newOrigin.setCountryOfOrigin(country);
                newOrigin.setApplication(application);
                application.getApplicationOrigins().add(newOrigin);
            }
        }
    }

    private CountryOfOrigin findOrCreateCountry(String normalizedName, String displayName) {
        return countryOfOriginRepository.findByName(normalizedName)
                .orElseGet(() -> {
                    CountryOfOrigin newCountry = new CountryOfOrigin();
                    newCountry.setName(normalizedName);
                    newCountry.setDisplayName(displayName);
                    return countryOfOriginRepository.save(newCountry);
                });
    }

    private boolean hasOrigin(Application application, CountryOfOrigin country) {
        return application.getApplicationOrigins().stream()
                .anyMatch(ao -> {
                    Long aoCountryId = ao.getCountryOfOrigin().getId();
                    if (aoCountryId != null && country.getId() != null) {
                        return aoCountryId.equals(country.getId());
                    }
                    return ao.getCountryOfOrigin().getName().equals(country.getName());
                });
    }

    /**
     * Processes tags for the application.
     */
    private void processTags(Application application, List<ApplicationTagDto> tagDtos, boolean isNewApplication) {
        if (tagDtos == null || tagDtos.isEmpty()) {
            return;
        }

        if (application.getApplicationApplicationTags() == null) {
            application.setApplicationApplicationTags(new java.util.HashSet<>());
        } else if (isNewApplication) {
            application.getApplicationApplicationTags().clear();
        }

        for (ApplicationTagDto tagDto : tagDtos) {
            if (tagDto == null || tagDto.name() == null || tagDto.tagType() == null) {
                continue;
            }

            ApplicationTag.ApplicationTagType tagType;
            try {
                tagType = ApplicationTag.ApplicationTagType.valueOf(tagDto.tagType());
            } catch (IllegalArgumentException e) {
                continue;
            }

            if (tagType == ApplicationTag.ApplicationTagType.DEPLOYMENT && "both".equalsIgnoreCase(tagDto.name())) {
                addDeploymentTagToApplication(application, "cloud-based");
                addDeploymentTagToApplication(application, "on-premise");
                continue;
            }

            ApplicationTag tag = findOrCreateTag(tagDto.name(), tagType);
            if (!hasTag(application, tag)) {
                ApplicationApplicationTag newAppTag = new ApplicationApplicationTag();
                newAppTag.setApplicationTag(tag);
                newAppTag.setApplication(application);
                application.getApplicationApplicationTags().add(newAppTag);
            }
        }
    }

    private ApplicationTag findOrCreateTag(String name, ApplicationTag.ApplicationTagType tagType) {
        return applicationTagRepository.findByNameAndTagType(name, tagType)
                .orElseGet(() -> {
                    ApplicationTag newTag = new ApplicationTag();
                    newTag.setName(name);
                    newTag.setTagType(tagType);
                    newTag.setDisplayName(name);
                    return applicationTagRepository.save(newTag);
                });
    }

    private boolean hasTag(Application application, ApplicationTag tag) {
        return application.getApplicationApplicationTags().stream()
                .anyMatch(aat -> {
                    Long aatTagId = aat.getApplicationTag().getId();
                    if (aatTagId != null && tag.getId() != null) {
                        return aatTagId.equals(tag.getId());
                    }
                    return aat.getApplicationTag().getName().equals(tag.getName())
                            && aat.getApplicationTag().getTagType().equals(tag.getTagType());
                });
    }

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
            application.setLifecycleState(Application.ApplicationLifecycleType.IN_PUBLISH_PROCESS);
        }

        // ImplementationVersion defaults
        if (implVersion.getLifecycleState() == null) {
            implVersion.setLifecycleState(ImplementationVersion.ImplementationVersionLifecycleType.IN_PUBLISH_PROCESS);
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
                .max((v1, v2) -> {
                    if (v1.getPublishDate() == null && v2.getPublishDate() == null) return 0;
                    if (v1.getPublishDate() == null) return -1;
                    if (v2.getPublishDate() == null) return 1;
                    return v1.getPublishDate().compareTo(v2.getPublishDate());
                })
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

        if (ConnectorBundle.FrameworkType.SCIM_REST.equals(bundle.getFramework())) {
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

    // ==================== Upload Connector Main Method ====================

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
        processOrigins(appRes.application(), appRes.originNames(), appRes.isNew());
        processTags(appRes.application(), appRes.tagDtos(), appRes.isNew());

        // 3. Set up relationships and defaults
        setUpRelationships(implRes, bundleVersion);
        setDefaults(appRes.application(), implRes.bundle(), bundleVersion, implVersion, implRes.implementation());
        copyFromLatestVersionIfNeeded(implRes, bundleVersion, implVersion);

        // 4. Handle external integrations
        createGitHubRepositoryIfNeeded(implRes, bundleVersion, implVersion, dto.files());

        // 5. Persist and trigger pipeline
        persistEntities(appRes, implRes, bundleVersion, implVersion);
        return triggerJenkinsPipeline(implVersion, implRes.implementation(), dto.implementation());
    }

    public String downloadConnector(String connectorVersion) {
        // TODO move impl from controller
        return null;
    }

    @Transactional
    public void successBuild(UUID oid, ContinueForm continueForm) {
        ImplementationVersion version = implementationVersionRepository.getReferenceById(oid);
        Implementation implementation = version.getImplementation();
//        String connectorBundleName = continueForm.getConnectorBundle();

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
                moveBundleVersionAndDeleteConnectorBundle(sourceBundle, targetBundle);

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


//        implementationRepository.save(implementation);
        implementationVersionRepository.save(version);
    }

    @Transactional
    public void failBuild(UUID oid, FailForm failForm) {
        ImplementationVersion version = implementationVersionRepository.getReferenceById(oid);
        version.setLifecycleState(ImplementationVersion.ImplementationVersionLifecycleType.WITH_ERROR);

        // Set error message on BundleVersion
        if (version.getBundleVersion() != null) {
            version.setErrorMessage(failForm.getErrorMessage());
        }

        Implementation implementation = version.getImplementation();
        Application application = implementation.getApplication();

        if (implementation.getImplementationVersions().size() == 1
                && application.getImplementations().size() == 1) {
            application.setLifecycleState(Application.ApplicationLifecycleType.WITH_ERROR);
        }

        implementationVersionRepository.save(version);
    }

    public Page<Application> searchApplication(SearchForm searchForm, int page, int size) {
        Specification<Application> spec = (root, query, cb) -> cb.conjunction();

        if (searchForm.getKeyword() != null && !searchForm.getKeyword().isBlank()) {
            String likePattern = "%" + searchForm.getKeyword().toLowerCase() + "%";
            spec = spec.and((root, query, cb) ->
                    cb.or(
                            cb.like(cb.lower(root.get("name")), likePattern),
                            cb.like(cb.lower(root.get("displayName")), likePattern),
                            cb.like(cb.lower(root.get("description")), likePattern)
                    ));
        }

        if (searchForm.getLifecycleState() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("applicationLifecycleType"), searchForm.getLifecycleState()));
        }

        if (searchForm.getApplicationTag() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("applicationTag"), searchForm.getApplicationTag()));
        }

        if (searchForm.getCountryOfOrigin() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("countryOfOrigin"), searchForm.getCountryOfOrigin()));
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by("id").ascending());
        return applicationRepository.findAll(spec, pageable);
    }

    public Page<ImplementationVersion> searchVersionsOfConnector(SearchForm searchForm, int page, int size
    ) {
        Specification<ImplementationVersion> spec = (root, query, cb) -> cb.conjunction();

        if (searchForm.getMaintainer() != null && !searchForm.getMaintainer().isBlank()) {
            spec = spec.and((root, query, cb) ->
                    cb.like(cb.lower(root.get("maintainer")), "%" + searchForm.getMaintainer() + "%"));
        }

        if (searchForm.getLifecycleState() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("applicationLifecycleType"), searchForm.getLifecycleState()));
        }

        if (searchForm.getApplicationId() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("application_id"), searchForm.getApplicationId()));
        }

        if (searchForm.getSystemVersion() != null && !searchForm.getSystemVersion().isBlank()) {
            spec = spec.and((root, query, cb) ->
                    cb.like(cb.lower(root.get("system_version")), "%" + searchForm.getSystemVersion() + "%"));
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by("id").ascending());
        return implementationVersionRepository.findAll(spec, pageable);
    }

    public List<Vote> getVotes() {
        return voteRepository.findAll();
    }

    public List<Request> getRequests() {
        return requestRepository.findAll();
    }

    public void recordDownloadIfNew(ImplementationVersion version, String ip, String userAgent, OffsetDateTime cutoff) {
        String browserName = com.evolveum.midpoint.integration.catalog.util.UserAgentParser.parseBrowserName(userAgent);
        String deviceType = com.evolveum.midpoint.integration.catalog.util.UserAgentParser.parseDeviceType(userAgent);
        String parsedUserAgent = browserName + "," + deviceType;

        boolean duplicate = downloadRepository
                .existsByImplementationVersionAndIpAddressAndUserAgentAndDownloadedAt(
                        version, ip, parsedUserAgent, cutoff);

        if (!duplicate) {
            Download dl = new Download();
            dl.setImplementationVersion(version);
            dl.setIpAddress(ip);
            dl.setUserAgent(parsedUserAgent);
            dl.setDownloadedAt(OffsetDateTime.now());
            downloadRepository.save(dl);
        }
    }

    /**
     * @deprecated This method is deprecated as capabilitiesType enum has been replaced with capabilities JSON field.
     * Use createRequestFromForm() instead for new request form submissions.
     */
    @Deprecated
    public Request createRequest(UUID applicationId, String capabilitiesType, String requester) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));

        Request r = new Request();
        r.setApplication(application);
        r.setRequester(requester);
        return requestRepository.save(r);
    }

    /**
     * Creates a new Application and Request from the request form submission.
     * The Application will be created with lifecycle state REQUESTED.
     *
     * @param dto RequestFormDto containing integrationApplicationName, deploymentType, description, capabilities, and systemVersion
     * @return The created Request entity
     */
    @Transactional
    public Request createRequestFromForm(RequestFormDto dto) {
        String integrationApplicationName = dto.integrationApplicationName();
        String description = dto.description();
        List<String> capabilities = dto.capabilities();
        String deploymentType = dto.deploymentType();
        String requester = dto.requester();

        // Generate abbreviated name: lowercase, spaces replaced with underscores, remove special characters
        String abbreviatedName = integrationApplicationName.toLowerCase()
                .replaceAll("[^a-z0-9_]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");

        // Check if application with this name already exists
        Optional<Application> existingApp = applicationRepository.findByName(abbreviatedName);
        if (existingApp.isPresent()) {
            // Append timestamp to make it unique
            abbreviatedName = abbreviatedName + "_" + System.currentTimeMillis();
        }

        try {
            // Create the application
            Application application = new Application();
            application.setName(abbreviatedName);
            application.setDisplayName(integrationApplicationName);
            application.setDescription(description != null ? description : "");
            application.setLifecycleState(Application.ApplicationLifecycleType.REQUESTED);

            // Save the application (UUID is auto-generated, timestamps are auto-set)
            application = applicationRepository.save(application);

            // Save deployment type tags to application_application_tag
            if (deploymentType != null && !deploymentType.isEmpty()) {
                saveDeploymentTypeTags(application, deploymentType);
            }

            // Check if a request already exists for this application
            if (requestRepository.existsByApplicationId(application.getId())) {
                throw new IllegalStateException("A request already exists for application: " + application.getDisplayName());
            }

            // Convert capabilities list to enum array
            ImplementationVersion.CapabilitiesType[] capabilitiesArray = null;
            if (capabilities != null && !capabilities.isEmpty()) {
                capabilitiesArray = capabilities.stream()
                        .map(cap -> ImplementationVersion.CapabilitiesType.valueOf(cap))
                        .toArray(ImplementationVersion.CapabilitiesType[]::new);
            }

            // Create the Request entity
            Request request = new Request();
            request.setApplication(application);
            request.setCapabilities(capabilitiesArray);
            request.setRequester(requester);

            return requestRepository.save(request);
        } catch (IllegalStateException e) {
            log.warn("Duplicate request attempt: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Failed to create request for application: {}", integrationApplicationName, e);
            throw new RuntimeException("Failed to create request: " + e.getMessage(), e);
        }
    }

    /**
     * Saves deployment type tags to application_application_tag table.
     * If deploymentType is "both", saves both "cloud-based" and "on-premise" tags.
     *
     * @param application The application to associate with the tags
     * @param deploymentType The deployment type: "on-premise", "cloud-based", or "both"
     */
    private void saveDeploymentTypeTags(Application application, String deploymentType) {
        if ("both".equalsIgnoreCase(deploymentType)) {
            // Save both cloud-based and on-premise tags
            saveDeploymentTag(application, "cloud-based");
            saveDeploymentTag(application, "on-premise");
        } else {
            // Save single deployment type tag
            saveDeploymentTag(application, deploymentType);
        }
    }

    /**
     * Saves a single deployment tag to application_application_tag table.
     *
     * @param application The application to associate with the tag
     * @param tagName The tag name (e.g., "cloud-based" or "on-premise")
     */
    private void saveDeploymentTag(Application application, String tagName) {
        Optional<ApplicationTag> tagOpt = applicationTagRepository.findByNameAndTagType(
                tagName, ApplicationTag.ApplicationTagType.DEPLOYMENT);

        if (tagOpt.isPresent()) {
            ApplicationApplicationTag appTag = new ApplicationApplicationTag();
            appTag.setApplication(application);
            appTag.setApplicationTag(tagOpt.get());
            applicationApplicationTagRepository.save(appTag);
        } else {
            log.warn("Deployment tag not found: {}", tagName);
        }
    }

    /**
     * Adds a deployment tag to an application's tag set (used during upload flow).
     * This method adds the tag to the application's in-memory set without saving to DB directly.
     *
     * @param application The application to add the tag to
     * @param tagName The tag name (e.g., "cloud-based" or "on-premise")
     */
    private void addDeploymentTagToApplication(Application application, String tagName) {
        Optional<ApplicationTag> tagOpt = applicationTagRepository.findByNameAndTagType(
                tagName, ApplicationTag.ApplicationTagType.DEPLOYMENT);

        if (tagOpt.isPresent()) {
            ApplicationTag existingTag = tagOpt.get();

            // Check if this tag already exists in the application
            boolean tagExists = application.getApplicationApplicationTags().stream()
                    .anyMatch(aat -> {
                        Long aatTagId = aat.getApplicationTag().getId();
                        if (aatTagId != null && existingTag.getId() != null) {
                            return aatTagId.equals(existingTag.getId());
                        } else {
                            return aat.getApplicationTag().getName().equals(existingTag.getName()) &&
                                   aat.getApplicationTag().getTagType().equals(existingTag.getTagType());
                        }
                    });

            if (!tagExists) {
                ApplicationApplicationTag newAppTag = new ApplicationApplicationTag();
                newAppTag.setApplicationTag(existingTag);
                newAppTag.setApplication(application);
                application.getApplicationApplicationTags().add(newAppTag);
            }
        } else {
            log.warn("Deployment tag not found: {}", tagName);
        }
    }

    public Optional<ImplementationVersion> findImplementationVersion(UUID id) {
        return implementationVersionRepository.findById(id);
    }

    public Optional<Request> getRequest(Long id) {
        return requestRepository.findById(id);
    }

    public Optional<Request> getRequestForApplication(UUID appId) {
        return requestRepository.findByApplicationId(appId);
    }

    /**
     * Submit a vote for a request.
     * Each user can only vote once per request (enforced by unique constraint).
     *
     * @param requestId The ID of the request to vote for
     * @param voter The username of the voter
     * @return The created Vote entity
     * @throws IllegalArgumentException if request not found or user already voted
     */
    public Vote submitVote(Long requestId, String voter) {
        Request request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found: " + requestId));

        // Check if user already voted
        if (voteRepository.existsByRequestIdAndVoter(requestId, voter)) {
            throw new IllegalArgumentException("User has already voted for this request");
        }

        Vote vote = new Vote();
        vote.setRequestId(requestId);
        vote.setVoter(voter);
        vote.setRequest(request);

        return voteRepository.save(vote);
    }

    /**
     * Get the vote count for a specific request.
     *
     * @param requestId The ID of the request
     * @return The number of votes
     */
    public long getVoteCount(Long requestId) {
        return voteRepository.countByRequestId(requestId);
    }

    /**
     * Check if a user has voted for a specific request.
     *
     * @param requestId The ID of the request
     * @param voter The username of the voter
     * @return true if user has voted, false otherwise
     */
    public boolean hasUserVoted(Long requestId, String voter) {
        return voteRepository.existsByRequestIdAndVoter(requestId, voter);
    }

    public byte[] downloadConnector(UUID versionId, String ip, String userAgent) throws IOException {
        ImplementationVersion version = implementationVersionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + versionId));

        // Get download link from BundleVersion
        String downloadLink = (version.getBundleVersion() != null) ?
                version.getBundleVersion().getDownloadLink() : null;

        if (downloadLink == null || downloadLink.isEmpty()) {
            throw new IllegalArgumentException("No download link available for version: " + versionId);
        }

        try (InputStream in = new URL(downloadLink).openStream()) {
            byte[] fileBytes = in.readAllBytes();

            OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(DOWNLOAD_OFFSET_SECONDS);
            recordDownloadIfNew(version, ip, userAgent, cutoff);

            return fileBytes;
        }
    }

    public List<ApplicationDto> getAllApplications() {
        return applicationRepository.findAll().stream()
                .map(app -> {
                    // For REQUESTED apps, get requestId and vote count
                    Long requestId = null;
                    Long voteCount = null;
                    if (app.getLifecycleState() == Application.ApplicationLifecycleType.REQUESTED) {
                        Optional<Request> request = getRequestForApplication(app.getId());
                        if (request.isPresent()) {
                            requestId = request.get().getId();
                            voteCount = getVoteCount(requestId);
                        }
                    }

                    // Use mapper to build DTO (capabilities and requester are null for list view)
                    return applicationMapper.mapToApplicationDto(app, null, null, requestId, voteCount);
                })
                .toList();
    }

    /**
     * List applications with pagination and optional filtering
     * @param pageable Pagination parameters
     * @param q Optional search query by name
     * @param featured Optional filter for featured applications
     * @return Page of ApplicationCardDto
     */
    public Page<ApplicationCardDto> list(Pageable pageable, String q, Boolean featured) {
        Page<Application> page;

        if (featured != null && featured) {
            page = applicationReadPort.findFeatured(pageable);
        } else if (q != null && !q.isBlank()) {
            page = applicationReadPort.searchByName(q.trim(), pageable);
        } else {
            page = applicationReadPort.findAll(pageable);
        }

        return page.map(applicationMapper::toCardDto);
    }


    // ==================== Verify Helper Methods ====================

    /**
     * Finds the source connector bundle containing the given implementation version.
     *
     * @param implementationVersionId the implementation version UUID
     * @return the ConnectorBundle containing this implementation version
     * @throws ResponseStatusException NOT_FOUND if no bundle contains this implementation version
     */
    private ConnectorBundle findSourceBundle(UUID implementationVersionId) {
        return connectorBundleRepository
                .findByBundleVersions_ImplementationVersions_Id(implementationVersionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No bundle found containing the connector Implementation version with OID " + implementationVersionId));
    }

    /**
     * Finds a connector bundle by its bundle name.
     *
     * @param bundleName the bundle name to search for
     * @return the ConnectorBundle with the given name
     * @throws ResponseStatusException NOT_FOUND if no bundle exists with this name
     */
    private ConnectorBundle findTargetBundle(String bundleName) {
        return connectorBundleRepository
                .findByBundleName(bundleName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No bundle found with bundle name " + bundleName));
    }

    /**
     * Finds an implementation version by ID.
     *
     * @param implementationVersionId the implementation version UUID
     * @return the ImplementationVersion
     * @throws ResponseStatusException NOT_FOUND if not found
     */
    private ImplementationVersion findImplementationVersionOrThrow(UUID implementationVersionId) {
        return implementationVersionRepository
                .findById(implementationVersionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No implementation version found with id " + implementationVersionId));
    }

    /**
     * Validates that the verify payload contains required fields.
     *
     * @param version the connector version string
     * @param className the connector class name
     * @param implVersion the implementation version to set error on if validation fails
     * @throws ResponseStatusException BAD_REQUEST if validation fails
     */
    private void validateVerifyPayload(String version, String className, ImplementationVersion implVersion) {
        if (version == null || version.isEmpty()) {
            String err = "Request payload lacks connector bundle version.";
            implVersion.setErrorMessage(err);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, err);
        }

        if (className == null || className.isEmpty()) {
            String err = "Request payload lacks connector className.";
            implVersion.setErrorMessage(err);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, err);
        }
    }

    /**
     * Checks if a bundle version already contains an implementation with the given class name.
     *
     * @param bundleVersion the bundle version to check
     * @param className the class name to look for
     * @param bundleName the bundle name (for error message)
     * @param version the version string (for error message)
     * @param implVersion the implementation version to set error on if conflict found
     * @throws ResponseStatusException CONFLICT if an implementation with this class name already exists
     */
    private void checkForClassNameConflict(BundleVersion bundleVersion, String className,
                                           String bundleName, String version, ImplementationVersion implVersion) {
        boolean hasConflict = bundleVersion.getImplementationVersions().stream()
                .anyMatch(iv -> className.equals(iv.getClassName()));

        if (hasConflict) {
            String err = "The connector bundle " + bundleName + " with the version " + version
                    + " already contains an implementation for the connector class " + className;
            implVersion.setErrorMessage(err);
            throw new ResponseStatusException(HttpStatus.CONFLICT, err);
        }
    }

    /**
     * Finds a bundle version within a connector bundle that matches the given version string.
     *
     * @param bundle the connector bundle to search in
     * @param version the version string to match
     * @return Optional containing the matching BundleVersion, or empty if not found
     */
    private Optional<BundleVersion> findMatchingBundleVersion(ConnectorBundle bundle, String version) {
        return bundle.getBundleVersions().stream()
                .filter(bv -> version.equals(bv.getConnectorVersion()))
                .findFirst();
    }

    /**
     * Sets error message on implementation version and throws ResponseStatusException.
     *
     * @param implVersion the implementation version to set error on
     * @param e the exception that occurred
     * @throws ResponseStatusException INTERNAL_SERVER_ERROR with the exception message
     */
    private void handleVerifyError(ImplementationVersion implVersion, Exception e) {
        String err = e.getLocalizedMessage();
        implVersion.setErrorMessage(err);
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, err);
    }

    // ==================== Verify Main Method ====================

    /**
     * Verify validity of the implementation version based on the data produced by the jenkins pipeline.
     * The process checks if a new connector bundle implementation version should be assigned to an existing connector-bundle.
     * Used in case of bundled connectors, i.e. Ldap contains an implementation (connector class) for Ldap connectors
     * but also an implementation version which handles AD based systems.
     *
     * @param verifyPayload Json form used to verify the validity of the implementation version.
     * @return Boolean signaling that the implementation version is valid and the Jenkins pipeline can proceed.
     */
    @Transactional
    public Boolean verify(VerifyBundleInformationForm verifyPayload) {
        UUID implementationVersionId = verifyPayload.getOid();
        String bundleName = verifyPayload.getBundleName();
        String version = verifyPayload.getVersion();
        String className = verifyPayload.getClassName();

        // 1. Find source bundle and implementation version
        ConnectorBundle sourceBundle = findSourceBundle(implementationVersionId);
        ImplementationVersion sourceImplVersion = findImplementationVersionOrThrow(implementationVersionId);

        // 2. Find target bundle by name
        ConnectorBundle targetBundle;
        try {
            targetBundle = findTargetBundle(bundleName);
        } catch (ResponseStatusException e) {
            sourceImplVersion.setErrorMessage(e.getReason());
            throw e;
        }

        // 3. Validate payload
        validateVerifyPayload(version, className, sourceImplVersion);

        // 4. Check if target bundle has a matching version
        Optional<BundleVersion> matchingBundleVersion = findMatchingBundleVersion(targetBundle, version);

        if (matchingBundleVersion.isPresent()) {
            // 5a. Version exists - check for conflict and move implementation version
            BundleVersion targetBundleVersion = matchingBundleVersion.get();
            checkForClassNameConflict(targetBundleVersion, className, bundleName, version, sourceImplVersion);

            try {
                moveImplVersionAndDeleteConnectorBundle(sourceBundle, targetBundleVersion, sourceImplVersion);
            } catch (Exception e) {
                handleVerifyError(sourceImplVersion, e);
            }
        } else {
            // 5b. Version doesn't exist - move all bundle versions to target
            try {
                moveBundleVersionAndDeleteConnectorBundle(sourceBundle, targetBundle);
            } catch (Exception e) {
                handleVerifyError(sourceImplVersion, e);
            }
        }

        return true;
    }
    @Transactional(rollbackFor = Exception.class)
    void moveBundleVersionAndDeleteConnectorBundle(ConnectorBundle sourceBundle,
                                                   ConnectorBundle targetBundle) {

        for (Implementation impl : sourceBundle.getImplementations()) {
            impl.setConnectorBundle(targetBundle);
            targetBundle.getImplementations().add(impl);
        }
        sourceBundle.getImplementations().clear();

        for (BundleVersion bv : sourceBundle.getBundleVersions()) {

            bv.setConnectorBundle(targetBundle);
            targetBundle.getBundleVersions().add(bv);
        }

        sourceBundle.getBundleVersions().clear();

        connectorBundleRepository.delete(sourceBundle);
    }


    @Transactional(rollbackFor = Exception.class)
    public void moveImplVersionAndDeleteConnectorBundle(ConnectorBundle sourceBundle,
        BundleVersion targetBundleVersion, ImplementationVersion sourceImplementationVersion) {

        if (sourceBundle.getBundleName() != null && !sourceBundle.getBundleName().isEmpty()) {

            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Illegal state of connector" +
                    " bundle. Bundle already contains implementation version which is being verified");
        }

        sourceImplementationVersion.setBundleVersion(targetBundleVersion);
        targetBundleVersion.getImplementationVersions().add(sourceImplementationVersion);
        ConnectorBundle targetBundle = targetBundleVersion.getConnectorBundle();
        for (BundleVersion bv : sourceBundle.getBundleVersions()) {

            for (ImplementationVersion iv : bv.getImplementationVersions()) {

                if (iv.getId().equals(sourceImplementationVersion.getId())) {
                    continue;
                }
                implementationVersionRepository.delete(iv);
            }
            bv.getImplementationVersions().clear();
            bv.setConnectorBundle(null);
        }
        sourceBundle.getBundleVersions().clear();

        for (Implementation impl : sourceBundle.getImplementations()) {
            impl.setConnectorBundle(targetBundle);
            targetBundle.getImplementations().add(impl);
        }
        sourceBundle.getImplementations().clear();
        connectorBundleRepository.delete(sourceBundle);
    }

    /**
     * Get implementations for a specific application.
     * Fetches the latest implementation version for each implementation.
     *
     * @param applicationId the application UUID
     * @return list of implementation DTOs with data from multiple tables
     */
    public List<com.evolveum.midpoint.integration.catalog.dto.ImplementationListItemDto> getImplementationsByApplicationId(UUID applicationId) {
        List<Implementation> implementations = implementationRepository.findByApplicationId(applicationId);

        return implementations.stream()
                .map(impl -> {
                    if (impl.getImplementationVersions() == null || impl.getImplementationVersions().isEmpty()) {
                        return null;
                    }

                    ImplementationVersion latestVersion = impl.getImplementationVersions().stream()
                            .max((v1, v2) -> {
                                if (v1.getPublishDate() == null && v2.getPublishDate() == null) return 0;
                                if (v1.getPublishDate() == null) return -1;
                                if (v2.getPublishDate() == null) return 1;
                                return v1.getPublishDate().compareTo(v2.getPublishDate());
                            })
                            .orElse(null);

                    if (latestVersion == null) {
                        return null;
                    }

                    return applicationMapper.mapToImplementationListItemDto(impl, latestVersion);
                })
                .filter(dto -> dto != null)
                .toList();
    }

    // ==================== Logo Management Methods ====================

    private static final Set<String> ALLOWED_LOGO_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/gif", "image/svg+xml", "image/webp"
    );

    /**
     * Uploads a logo for an application.
     * Deletes old file if present, saves new file, updates DB fields, and persists.
     *
     * @param applicationId the application UUID
     * @param file the logo file to upload
     * @return the updated application
     * @throws IOException if file operation fails
     * @throws IllegalArgumentException if file validation fails
     */
    @Transactional
    public Application uploadLogo(UUID applicationId, MultipartFile file) throws IOException {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Application not found with id: " + applicationId));

        // Validate file
        validateLogoFile(file);

        // Delete old logo file if present
        deleteLogoFileIfExists(application.getLogoPath());

        // Generate safe filename (UUID-based)
        String originalFilename = file.getOriginalFilename();
        String extension = getFileExtension(originalFilename);
        String safeFilename = UUID.randomUUID().toString() + extension;

        // Save file to disk
        Path targetPath = Path.of(logoStorageProperties.basePath()).resolve(safeFilename);
        Files.createDirectories(targetPath.getParent());
        Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

        // Update DB fields
        application.setLogoPath(safeFilename);
        application.setLogoContentType(file.getContentType());
        application.setLogoOriginalName(originalFilename);
        application.setLogoSizeBytes(file.getSize());

        log.info("Uploaded logo for application {}: {} ({} bytes)",
                applicationId, safeFilename, file.getSize());

        return applicationRepository.save(application);
    }

    /**
     * Gets logo bytes for an application.
     * Pattern 1: Returns null if no logo (caller handles 404).
     *
     * @param applicationId the application UUID
     * @return LogoData with bytes and metadata, or null if no logo
     */
    public LogoData getLogoOrNull(UUID applicationId) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Application not found with id: " + applicationId));

        // Check file-based logo first
        if (application.getLogoPath() != null && !application.getLogoPath().isBlank()) {
            Path logoPath = Path.of(logoStorageProperties.basePath()).resolve(application.getLogoPath());
            if (Files.exists(logoPath)) {
                try {
                    byte[] bytes = Files.readAllBytes(logoPath);
                    return new LogoData(
                            bytes,
                            application.getLogoContentType(),
                            application.getLogoOriginalName(),
                            application.getLogoSizeBytes(),
                            application.getLogoPath()
                    );
                } catch (IOException e) {
                    log.error("Failed to read logo file: {}", logoPath, e);
                    return null;
                }
            }
        }

        return null; // No logo available
    }

    /**
     * Gets logo bytes for an application.
     * Pattern 2: Returns placeholder if no logo.
     *
     * @param applicationId the application UUID
     * @param placeholderBytes the placeholder image bytes to return if no logo
     * @param placeholderContentType the content type of the placeholder
     * @return LogoData with bytes and metadata (never null)
     */
    public LogoData getLogoOrPlaceholder(UUID applicationId, byte[] placeholderBytes, String placeholderContentType) {
        LogoData logo = getLogoOrNull(applicationId);
        if (logo != null) {
            return logo;
        }

        // Return placeholder
        return new LogoData(
                placeholderBytes,
                placeholderContentType,
                "placeholder.png",
                (long) placeholderBytes.length,
                null
        );
    }

    /**
     * Deletes logo for an application.
     * Removes file from disk and clears DB fields.
     *
     * @param applicationId the application UUID
     * @return the updated application
     */
    @Transactional
    public Application deleteLogo(UUID applicationId) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Application not found with id: " + applicationId));

        // Delete file from disk
        deleteLogoFileIfExists(application.getLogoPath());

        // Clear all logo fields
        application.setLogoPath(null);
        application.setLogoContentType(null);
        application.setLogoOriginalName(null);
        application.setLogoSizeBytes(null);

        log.info("Deleted logo for application {}", applicationId);

        return applicationRepository.save(application);
    }

    /**
     * Validates the logo file for size and content type.
     */
    private void validateLogoFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Logo file is required");
        }

        if (file.getSize() > logoStorageProperties.maxSizeBytes()) {
            throw new IllegalArgumentException(
                    String.format("Logo file size %d bytes exceeds maximum allowed size of %d bytes",
                            file.getSize(), logoStorageProperties.maxSizeBytes()));
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_LOGO_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException(
                    String.format("Invalid content type '%s'. Allowed: %s",
                            contentType, ALLOWED_LOGO_CONTENT_TYPES));
        }
    }

    /**
     * Deletes a logo file from disk if it exists.
     */
    private void deleteLogoFileIfExists(String logoPath) {
        if (logoPath == null || logoPath.isBlank()) {
            return;
        }

        Path filePath = Path.of(logoStorageProperties.basePath()).resolve(logoPath);
        try {
            if (Files.exists(filePath)) {
                Files.delete(filePath);
                log.info("Deleted logo file: {}", filePath);
            }
        } catch (IOException e) {
            log.error("Failed to delete logo file: {}", filePath, e);
        }
    }

    /**
     * Extracts file extension from filename.
     */
    private String getFileExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int lastDot = filename.lastIndexOf('.');
        return lastDot == -1 ? "" : filename.substring(lastDot);
    }

    /**
     * DTO for returning logo data with metadata.
     */
    public record LogoData(
            byte[] bytes,
            String contentType,
            String originalName,
            Long sizeBytes,
            String path
    ) {
        /**
         * Generates an ETag for caching.
         */
        public String generateETag() {
            if (path != null && sizeBytes != null) {
                return "\"" + path.hashCode() + "-" + sizeBytes + "\"";
            }
            return "\"" + java.util.Arrays.hashCode(bytes) + "\"";
        }
    }
}
