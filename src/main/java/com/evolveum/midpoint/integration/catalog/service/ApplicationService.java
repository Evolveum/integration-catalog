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
import com.evolveum.midpoint.integration.catalog.repository.adapter.InetAddress;

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
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
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
                              ApplicationApplicationTagRepository applicationApplicationTagRepository
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
     * Method upload connector to integration catalog and return link to git repository at the successful processing and failure message at a processing failure.
     * The connector is stored on GitHub in case there is no GitHub repositor of the connector and then upload to nexus with a use jenkins job.
     * @param dto UploadImplementationDto containing application, implementation, implementationVersion, and files
     * @return
     */
    public String uploadConnector(UploadImplementationDto dto, String username) {
        Application application = dto.application();
        ImplementationDTO implementationDto = dto.implementation();
        Implementation implementation = new Implementation();
        implementation.setDisplayName(implementationDto.displayName());
        List<ItemFile> files = dto.files();

        boolean isNewVersionOfExistingImplementation = false;
        boolean isNewApplication = (application.getId() == null);
        Implementation existingImplementation = null;

        // Get origins and tags from the application DTO
        // Frontend sends simple arrays: origins = ["country1", "country2"], tags = [{name: "tag1", tagType: "CATEGORY"}]
        List<String> originNames = application.getOrigins();
        List<ApplicationTagDto> tagDtos = application.getTags();

        if (application.getId() != null) {
            Optional<Application> existApplication = applicationRepository.findById(application.getId());
            application = existApplication.orElseThrow(() -> new RuntimeException("Application not found"));
        } else {
            // For new applications, generate name from displayName if not set
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

        implementation.setApplication(application);

        // Check if we're adding a new version to an existing implementation
        ConnectorBundle connectorBundle;
        if (implementationDto.implementationId() != null) {
            // Adding a new version to existing implementation
            Optional<Implementation> existImpl = implementationRepository.findById(implementationDto.implementationId());
            if (existImpl.isPresent()) {
                isNewVersionOfExistingImplementation = true;
                existingImplementation = existImpl.get();
                implementation = existingImplementation;
                // Use the existing connector bundle
                connectorBundle = existingImplementation.getConnectorBundle();
            } else {
                throw new RuntimeException("Implementation not found with id: " + implementationDto.implementationId());
            }
        } else {
            // New implementation - create new connector bundle from DTO data
            // Determine framework: if buildFramework is MAVEN → CONNID, otherwise → SCIM_REST
            ConnectorBundle.FrameworkType framework = implementationDto.framework();
            if (framework == null && implementationDto.buildFramework() != null) {
                // Auto-set framework based on build type
                framework = (implementationDto.buildFramework() == BundleVersion.BuildFrameworkType.MAVEN)
                        ? ConnectorBundle.FrameworkType.CONNID
                        : ConnectorBundle.FrameworkType.SCIM_REST;
            }

            if (framework == null) {
                throw new IllegalArgumentException("Framework must be specified or buildFramework must be provided");
            }

            connectorBundle = new ConnectorBundle();
            connectorBundle.setFramework(framework);
            connectorBundle.setLicense(implementationDto.license());
            connectorBundle.setBundleName(implementationDto.bundleName());
            connectorBundle.setMaintainer(implementationDto.maintainer());
            connectorBundle.setTicketingSystemLink(implementationDto.ticketingSystemLink());
        }

        BundleVersion bundleVersion = new BundleVersion();
        bundleVersion.setConnectorVersion( implementationDto.connectorVersion());
        bundleVersion.setConnectorBundle(connectorBundle);
        bundleVersion.setBuildFramework(implementationDto.buildFramework());

        bundleVersion.setPathToProject(implementationDto.pathToProject());
        bundleVersion.setBrowseLink(implementationDto.browseLink());
        bundleVersion.setCheckoutLink(implementationDto.checkoutLink());

        ImplementationVersion implementationVersion = new ImplementationVersion();
        String description = implementationDto.description();
        implementationVersion.setDescription(description);

        // Process ApplicationOrigin - allow updating origins in all scenarios:
        // 1. Creating new application
        // 2. Creating new implementation for existing application
        // 3. Adding new version to existing implementation (can still modify application origins)
        if (originNames != null && !originNames.isEmpty()) {
            // Initialize or clear the set to avoid transient entity issues
            if (application.getApplicationOrigins() == null) {
                application.setApplicationOrigins(new java.util.HashSet<>());
            } else if (isNewApplication) {
                // Clear any transient entities from DTO for new applications
                application.getApplicationOrigins().clear();
            }

            // Process origin names - ensure CountryOfOrigin entities exist
            try {
                for (String countryDisplayName : originNames) {
                    if (countryDisplayName == null || countryDisplayName.trim().isEmpty()) {
                        continue;
                    }

                    // Normalize country name: lowercase with underscores
                    String normalizedName = countryDisplayName.toLowerCase().replaceAll("\\s+", "_");

                    // Look up existing country by normalized name, or create new one
                    CountryOfOrigin existingCountry = countryOfOriginRepository.findByName(normalizedName)
                            .orElseGet(() -> {
                                CountryOfOrigin newCountry = new CountryOfOrigin();
                                newCountry.setName(normalizedName);
                                newCountry.setDisplayName(countryDisplayName);
                                return countryOfOriginRepository.save(newCountry);
                            });

                    // Check if this origin already exists in the application
                    boolean originExists = application.getApplicationOrigins().stream()
                            .anyMatch(ao -> {
                                Long aoCountryId = ao.getCountryOfOrigin().getId();
                                if (aoCountryId != null && existingCountry.getId() != null) {
                                    // Compare by ID if both are available
                                    return aoCountryId.equals(existingCountry.getId());
                                } else {
                                    // Compare by name if IDs are not available
                                    return ao.getCountryOfOrigin().getName().equals(existingCountry.getName());
                                }
                            });

                    if (!originExists) {
                        // Create new ApplicationOrigin with proper relationships
                        ApplicationOrigin newAppOrigin = new ApplicationOrigin();
                        newAppOrigin.setCountryOfOrigin(existingCountry);
                        newAppOrigin.setApplication(application);
                        application.getApplicationOrigins().add(newAppOrigin);
                    }
                }
            } catch (Exception e) {
                log.error("Error processing origins: {}", e.getMessage(), e);
                throw e;
            }
        }

        // Process ApplicationApplicationTag - allow updating tags in all scenarios
        if (tagDtos != null && !tagDtos.isEmpty()) {
            // Initialize or clear the set to avoid transient entity issues
            if (application.getApplicationApplicationTags() == null) {
                application.setApplicationApplicationTags(new java.util.HashSet<>());
            } else if (isNewApplication) {
                // Clear any transient entities from DTO for new applications
                application.getApplicationApplicationTags().clear();
            }

            // Process tag DTOs - ensure ApplicationTag entities exist
            for (ApplicationTagDto tagDto : tagDtos) {
                if (tagDto == null || tagDto.name() == null || tagDto.tagType() == null) {
                    continue;
                }

                // Parse tagType string to enum
                ApplicationTag.ApplicationTagType tagType;
                try {
                    tagType = ApplicationTag.ApplicationTagType.valueOf(tagDto.tagType());
                } catch (IllegalArgumentException e) {
                    continue;
                }

                // Handle special case for DEPLOYMENT type with "both" value
                if (tagType == ApplicationTag.ApplicationTagType.DEPLOYMENT && "both".equalsIgnoreCase(tagDto.name())) {
                    // Add both cloud-based and on-premise tags
                    addDeploymentTagToApplication(application, "cloud-based");
                    addDeploymentTagToApplication(application, "on-premise");
                    continue;
                }

                // Look up existing tag by name and tagType
                ApplicationTag existingTag = applicationTagRepository.findByNameAndTagType(
                        tagDto.name(),
                        tagType
                ).orElseGet(() -> {
                    ApplicationTag newTag = new ApplicationTag();
                    newTag.setName(tagDto.name());
                    newTag.setTagType(tagType);
                    newTag.setDisplayName(tagDto.name()); // Use name as displayName if not provided
                    return applicationTagRepository.save(newTag);
                });

                // Check if this tag already exists in the application
                boolean tagExists = application.getApplicationApplicationTags().stream()
                        .anyMatch(aat -> {
                            Long aatTagId = aat.getApplicationTag().getId();
                            if (aatTagId != null && existingTag.getId() != null) {
                                // Compare by ID if both are available
                                return aatTagId.equals(existingTag.getId());
                            } else {
                                // Compare by name and type if IDs are not available
                                return aat.getApplicationTag().getName().equals(existingTag.getName()) &&
                                       aat.getApplicationTag().getTagType().equals(existingTag.getTagType());
                            }
                        });

                if (!tagExists) {
                    // Create new ApplicationApplicationTag with proper relationships
                    ApplicationApplicationTag newAppTag = new ApplicationApplicationTag();
                    newAppTag.setApplicationTag(existingTag);
                    newAppTag.setApplication(application);
                    application.getApplicationApplicationTags().add(newAppTag);
                }
            }
        }

        // Set relationships
        if (!isNewVersionOfExistingImplementation) {
            // Only set these relationships when creating a new implementation
            implementation.setApplication(application);
            implementation.setConnectorBundle(connectorBundle);
        }
        bundleVersion.setConnectorBundle(connectorBundle);
        implementationVersion.setImplementation(implementation);
        implementationVersion.setBundleVersion(bundleVersion);

        // Set default values for required fields
        if (application.getLifecycleState() == null) {
            application.setLifecycleState(Application.ApplicationLifecycleType.IN_PUBLISH_PROCESS);
        }

        if (implementationVersion.getLifecycleState() == null) {
            implementationVersion.setLifecycleState(ImplementationVersion.ImplementationVersionLifecycleType.IN_PUBLISH_PROCESS);
        }

        if (implementationVersion.getPublishDate() == null) {
            implementationVersion.setPublishDate(OffsetDateTime.now());
        }

        // Set ConnectorBundle defaults
        if (connectorBundle.getBundleName() == null) {
            // Generate bundle name from implementation display name
            String bundleName = implementation.getDisplayName() != null
                ? implementation.getDisplayName().toLowerCase().replaceAll("[^a-z0-9]", "-")
                : "connector-bundle";
            connectorBundle.setBundleName(bundleName);
        }
        if (connectorBundle.getLicense() == null) {
            connectorBundle.setLicense(ConnectorBundle.LicenseType.APACHE_2);
        }
        // Note: When isNewVersionOfExistingImplementation is true, we're already using
        // the existing ConnectorBundle, so no need to copy fields

        // Set BundleVersion defaults
        if (bundleVersion.getConnectorVersion() == null) {
            bundleVersion.setConnectorVersion(null);
        }
        // downloadLink will be set by Jenkins or copied from previous version - no default needed
        if (bundleVersion.getConnidVersion() == null) {
            bundleVersion.setConnidVersion(null);
        }
        if (bundleVersion.getReleasedDate() == null) {
            bundleVersion.setReleasedDate(java.time.LocalDate.now());
        }
        if (bundleVersion.getBuildFramework() == null) {
            bundleVersion.setBuildFramework(BundleVersion.BuildFrameworkType.MAVEN);
        }

        // Set ImplementationVersion defaults
        if (implementationVersion.getSystemVersion() == null) {
            implementationVersion.setSystemVersion(null);
        }
        if (implementationVersion.getClassName() == null) {
            implementationVersion.setClassName(null);
        }
        if (implementationVersion.getAuthor() == null) {
            implementationVersion.setAuthor(username);
        }

        // Copy capabilities and bundle version fields from latest version if adding new version to existing implementation
        if (isNewVersionOfExistingImplementation) {
            if (existingImplementation.getImplementationVersions() != null &&
                !existingImplementation.getImplementationVersions().isEmpty()) {
                ImplementationVersion latestVersion = existingImplementation.getImplementationVersions().stream()
                    .max((v1, v2) -> {
                        if (v1.getPublishDate() == null && v2.getPublishDate() == null) return 0;
                        if (v1.getPublishDate() == null) return -1;
                        if (v2.getPublishDate() == null) return 1;
                        return v1.getPublishDate().compareTo(v2.getPublishDate());
                    })
                    .orElse(null);

                if (latestVersion != null) {
                    // Copy capabilities if not set
                    if (implementationVersion.getCapabilities() == null && latestVersion.getCapabilities() != null) {
                        implementationVersion.setCapabilities(latestVersion.getCapabilities());
                    }

                    // Copy bundle version fields if not set
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
            }
        }

        // Clear error message - we don't want to store GitHub errors in the database
        implementationVersion.setErrorMessage(null);

        // Check framework from ConnectorBundle - only create GitHub repo for new implementations
        if (!isNewVersionOfExistingImplementation) {
            if (ConnectorBundle.FrameworkType.SCIM_REST.equals(connectorBundle.getFramework())) {
                if (!(bundleVersion.getCheckoutLink() != null && !bundleVersion.getCheckoutLink().isEmpty()
                        && bundleVersion.getBrowseLink() != null && !bundleVersion.getBrowseLink().isEmpty())) {
                    try {
                        GithubClient githubClient = new GithubClient(githubProperties);
                        GHRepository repository = githubClient.createProject(implementation.getDisplayName(), implementationVersion, files);

                        // Store repository links in BundleVersion
                        bundleVersion.setCheckoutLink(repository.getHttpTransportUrl());
                        bundleVersion.setBrowseLink(repository.getHtmlUrl().toString() + "/tree/main");
                    } catch (Exception e) {
                        // Store error in BundleVersion
                        implementationVersion.setErrorMessage(e.getMessage());
                        log.error(e.getMessage());
                    }
                }
            } else if (ConnectorBundle.FrameworkType.CONNID.equals(connectorBundle.getFramework())) {
                // For CONNID framework, use the provided links from DTO
                if (implementationDto.checkoutLink() != null) {
                    bundleVersion.setCheckoutLink(implementationDto.checkoutLink());
                }
                if (implementationDto.browseLink() != null) {
                    bundleVersion.setBrowseLink(implementationDto.browseLink());
                }
            }
        }

        // Save entities in correct order to respect foreign key constraints
        if (isNewVersionOfExistingImplementation) {
            // When adding a new version to existing implementation:
            // - Application already exists (don't save)
            // - ConnectorBundle already exists (using existing one, don't save)
            // - Implementation already exists (don't save)
            // - Only save new BundleVersion and ImplementationVersion
            bundleVersionRepository.save(bundleVersion);
            implementationVersionRepository.save(implementationVersion);
        } else {
            // When creating a new implementation:
            // - Save all entities in correct order
            applicationRepository.save(application);
            connectorBundleRepository.save(connectorBundle);
            bundleVersionRepository.save(bundleVersion);
            implementationRepository.save(implementation);
            implementationVersionRepository.save(implementationVersion);
        }

        // Check for errors in BundleVersion
        String errorMessage = (implementationVersion.getBundleVersion() != null) ?
                implementationVersion.getErrorMessage() : null;

        if (errorMessage == null) {
            try {
                // Get data from BundleVersion and ConnectorBundle for Jenkins
                String checkoutLink = implementationVersion.getBundleVersion() != null ?
                        implementationVersion.getBundleVersion().getCheckoutLink() : "";
                String browseLink = implementationVersion.getBundleVersion() != null ?
                        implementationVersion.getBundleVersion().getBrowseLink() : "";
                String framework = implementation.getConnectorBundle() != null ?
                        implementation.getConnectorBundle().getFramework().name() : "";
                String className = implementationDto.className() != null ?
                        implementationDto.className() : "";
                String pathToProject = implementationDto.pathToProject() != null ?
                        implementationDto.pathToProject() : "";

                JenkinsClient jenkinsClient = new JenkinsClient(jenkinsProperties);
                HttpResponse<String> response = jenkinsClient.triggerJob(
                        "integration-catalog-upload-connid-connector",
                        Map.of("REPOSITORY_URL", checkoutLink,
                                "BRANCH_URL", browseLink,
                                "CONNECTOR_OID", implementationVersion.getId().toString(),
                                "IMPL_TITLE", implementation.getDisplayName(),
                                "IMPL_FRAMEWORK", framework,
                                "SKIP_DEPLOY", "false",
                                "CONNECTOR_CLASS", className,
                                "PATH_TO_PROJECT", pathToProject));
                log.info(response.body());
                return response.body();
            } catch (Exception e) {
                if (implementationVersion.getBundleVersion() != null) {
                    implementationVersion.setErrorMessage(e.getMessage());
                    implementationVersionRepository.save(implementationVersion);
                }
                log.error(e.getMessage());
                errorMessage = e.getMessage();
            }
        }

        return errorMessage;
    }

    public String downloadConnector(String connectorVersion) {
        // TODO move impl from controller
        return null;
    }

    public void successBuild(UUID oid, ContinueForm continueForm) {
        ImplementationVersion version = implementationVersionRepository.getReferenceById(oid);

        // Update BundleVersion with build information
        if (version.getBundleVersion() != null) {
            version.getBundleVersion().setConnectorVersion(continueForm.getConnectorVersion());
            version.getBundleVersion().setDownloadLink(continueForm.getDownloadLink());
        }

        // Update ConnectorBundle name if provided
        Implementation implementation = version.getImplementation();
        if (implementation.getConnectorBundle() != null && continueForm.getConnectorBundle() != null) {
            implementation.getConnectorBundle().setBundleName(continueForm.getConnectorBundle());
        }

        OffsetDateTime odt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(continueForm.getPublishTime()), ZoneOffset.UTC);
        version.setPublishDate(odt)
                .setLifecycleState(ImplementationVersion.ImplementationVersionLifecycleType.ACTIVE);
        version.setCapabilities(continueForm.getCapability().toArray(new ImplementationVersion.CapabilitiesType[0]));
        version.setClassName(continueForm.getConnectorClass());

        implementationRepository.save(implementation);
        implementationVersionRepository.save(version);
    }

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

    public void recordDownloadIfNew(ImplementationVersion version, InetAddress ip, String userAgent, OffsetDateTime cutoff) {
        boolean duplicate = downloadRepository
                .existsByImplementationVersionAndIpAddressAndUserAgentAndDownloadedAt(version, ip, userAgent, cutoff);

        if (!duplicate) {
            Download dl = new Download();
            dl.setImplementationVersion(version);
            dl.setIpAddress(ip);
            dl.setUserAgent(userAgent);
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

            InetAddress inet = new InetAddress(ip);
            OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(DOWNLOAD_OFFSET_SECONDS);
            recordDownloadIfNew(version, inet, userAgent, cutoff);

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

        return page.map(this::toCard);
    }

    /**
     * Convert Application entity to ApplicationCardDto for list display
     * @param app Application entity
     * @return ApplicationCardDto
     */
    private ApplicationCardDto toCard(Application app) {
        String lifecycleState = app.getLifecycleState() != null ? app.getLifecycleState().name() : null;

        // Convert origins from ApplicationOrigin join table
        List<CountryOfOriginDto> origins = null;
        if (app.getApplicationOrigins() != null) {
            origins = app.getApplicationOrigins().stream()
                    .map(appOrigin -> new CountryOfOriginDto(
                            appOrigin.getCountryOfOrigin().getId(),
                            appOrigin.getCountryOfOrigin().getName(),
                            appOrigin.getCountryOfOrigin().getDisplayName()
                    ))
                    .toList();
        }

        // Convert categories and tags from ApplicationApplicationTag join table
        List<ApplicationTagDto> categories = null;
        List<ApplicationTagDto> tags = null;
        if (app.getApplicationApplicationTags() != null) {
            categories = app.getApplicationApplicationTags().stream()
                    .filter(aat -> aat.getApplicationTag().getTagType() == ApplicationTag.ApplicationTagType.CATEGORY)
                    .map(aat -> new ApplicationTagDto(
                            aat.getApplicationTag().getId(),
                            aat.getApplicationTag().getName(),
                            aat.getApplicationTag().getDisplayName(),
                            aat.getApplicationTag().getTagType().name()
                    ))
                    .toList();

            tags = app.getApplicationApplicationTags().stream()
                    .filter(aat -> aat.getApplicationTag().getTagType() != ApplicationTag.ApplicationTagType.CATEGORY)
                    .map(aat -> new ApplicationTagDto(
                            aat.getApplicationTag().getId(),
                            aat.getApplicationTag().getName(),
                            aat.getApplicationTag().getDisplayName(),
                            aat.getApplicationTag().getTagType().name()
                    ))
                    .toList();
        }

        // Get request info if lifecycle state is REQUESTED
        Long requestId = null;
        Long voteCount = null;
        if (app.getLifecycleState() == Application.ApplicationLifecycleType.REQUESTED) {
            Optional<Request> requestOpt = requestRepository.findByApplicationId(app.getId());
            if (requestOpt.isPresent()) {
                Request request = requestOpt.get();
                requestId = request.getId();
                voteCount = voteRepository.countByRequestId(request.getId());
            }
        }

        // Extract frameworks from implementations
        List<String> frameworks = applicationMapper.extractFrameworks(app);

        return new ApplicationCardDto(
                app.getId(),
                app.getDisplayName(),
                app.getDescription(),
                app.getLogo(),
                lifecycleState,
                origins,
                categories,
                tags,
                requestId,
                voteCount,
                frameworks
        );
    }


    /**
     * Verify validity of the implementation version based on the data produced by the jenkins pipeline.
     * The process checks id a new connector bundle implementation version should be assigned to a existing connector-bundle.
     * Used in case of bundled connectors, i.e. Ldap contains an implementation (connector class) for Ldap connectors
     * but also an implementation version which handles AD based systems.
     *
     * @param verifiPayload Json form used to verify the validity of the implementation version.
     * @return Boolean signaling that the implementation version is valid and the Jenkins pipeline can proceed.
     */
    public Boolean verify(VerifyBundleInformationForm verifiPayload) {

        String bundleName = verifiPayload.getBundleName();
        String err;
        Optional<ConnectorBundle> bundle = connectorBundleRepository.findByBundleName(bundleName);
        UUID implementationVersionId = verifiPayload.getOid();
        Optional<ConnectorBundle> connectorBundle = connectorBundleRepository.
                findByBundleVersions_ImplementationVersions_Id(implementationVersionId);

        if (connectorBundle.isEmpty()) {
            err = "No bundle found containing the connector Implementation version with OID " + implementationVersionId + ".";
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, err);
        }

        Optional<ImplementationVersion> iVersion = implementationVersionRepository.
                findById(implementationVersionId);

        if(iVersion.isEmpty()){
            err = "No implementation version found with id " + implementationVersionId + ".";
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, err);
        }

        ImplementationVersion sourceImplementationVersion = iVersion.get();
        if (bundle.isEmpty()) {

            err = "No bundle found with bundle name " + bundleName + ".";
            sourceImplementationVersion.setErrorMessage(err);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, err);
        } else {
            String version = verifiPayload.getVersion();
            String className = verifiPayload.getClassName();

            if (!(version != null && !version.isEmpty())) {

                err = "Request payload lacks connector bundle version. ";
                sourceImplementationVersion.setErrorMessage(err);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, err);
            }

            if (!(className != null && !className.isEmpty())) {

                err = "Request payload lacks connector className. ";
                sourceImplementationVersion.setErrorMessage(err);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, err);
            }

            List<BundleVersion> bundleVersions = bundle.get().getBundleVersions();
            for (BundleVersion bundleVersion : bundleVersions) {
                if (version.equals(bundleVersion.getConnectorVersion())) {
                    List<ImplementationVersion> implementationVersions = bundleVersion.getImplementationVersions();
                    for (ImplementationVersion implementationVersion : implementationVersions) {
                        if (className.equals(implementationVersion.getClassName())) {

                            err = "The connector bundle " + bundleName + " with the version "
                                    + version + " already contains a implementation" + " for the connector class "
                                    + className;
                            sourceImplementationVersion.setErrorMessage(err);
                            throw new ResponseStatusException(HttpStatus.CONFLICT, err);
                        }
                    }
                    try {

                        moveImplVersionAndDeleteConnectorBundle(connectorBundle.get(),
                                bundleVersion, sourceImplementationVersion);
                    } catch (Exception e) {

                        err = e.getLocalizedMessage();
                        sourceImplementationVersion.setErrorMessage(err);
                        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, err);
                    }

                    return true;
                }
            }

            ///  Handle this step, link to the correct bundle.
            err = "No connector bundle found for " +
                    "bundle name " + bundleName + ", version " + version + " and connector class " + className;
            sourceImplementationVersion.setErrorMessage(err);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, err);
        }
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
}
