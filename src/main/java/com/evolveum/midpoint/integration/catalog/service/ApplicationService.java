/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.ApplicationDto;
import com.evolveum.midpoint.integration.catalog.dto.ApplicationCardDto;
import com.evolveum.midpoint.integration.catalog.dto.ApplicationTagDto;
import com.evolveum.midpoint.integration.catalog.dto.CategoryCountDto;
import com.evolveum.midpoint.integration.catalog.dto.CountryOfOriginDto;
import com.evolveum.midpoint.integration.catalog.dto.RequestFormDto;
import com.evolveum.midpoint.integration.catalog.dto.UploadImplementationDto;
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
import org.springframework.stereotype.Service;

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
    private final ConnectorBundleRepository connectorBundleRepository;

    @Autowired
    private final BundleVersionRepository bundleVersionRepository;

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
                              ApplicationMapper applicationMapper
    ) {
        this.applicationRepository = applicationRepository;
        this.applicationTagRepository = applicationTagRepository;
        this.countryOfOriginRepository = countryOfOriginRepository;
        this.implementationRepository = implementationRepository;
        this.implementationVersionRepository = implementationVersionRepository;
        this.connectorBundleRepository = connectorBundleRepository;
        this.bundleVersionRepository = bundleVersionRepository;
        this.connidVersionRepository = connidVersionRepository;
        this.githubProperties = githubProperties;
        this.jenkinsProperties = jenkinsProperties;
        this.downloadRepository = downloadRepository;
        this.requestRepository = requestRepository;
        this.voteRepository = voteRepository;
        this.applicationReadPort = applicationReadPort;
        this.applicationMapper = applicationMapper;
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
        Implementation implementation = dto.implementation();
        ConnectorBundle connectorBundle = dto.connectorBundle();
        BundleVersion bundleVersion = dto.bundleVersion();
        ImplementationVersion implementationVersion = dto.implementationVersion();
        List<ItemFile> files = dto.files();

        log.info("Upload connector called - Implementation description from DTO: {}", implementationVersion.getDescription());

        boolean isNewVersionOfExistingImplementation = false;
        boolean isNewApplication = (application.getId() == null);
        Implementation existingImplementation = null;

        // Store origins and tags from DTO before potentially replacing application
        Set<ApplicationOrigin> dtoOrigins = application.getApplicationOrigins();
        Set<ApplicationApplicationTag> dtoTags = application.getApplicationApplicationTags();

        log.info("Upload details - isNewApplication: {}, application.getId(): {}, dtoOrigins: {}, dtoOrigins size: {}",
                isNewApplication,
                application.getId(),
                dtoOrigins != null ? "not null" : "null",
                dtoOrigins != null ? dtoOrigins.size() : 0);

        if (application.getId() != null) {
            Optional<Application> existApplication = applicationRepository.findById(application.getId());
            application = existApplication.orElseThrow(() -> new RuntimeException("Application not found"));
        }

        // Check if we're adding a new version to an existing implementation
        if (implementation.getId() != null) {
            Optional<Implementation> existImpl = implementationRepository.findById(implementation.getId());
            if (existImpl.isPresent()) {
                isNewVersionOfExistingImplementation = true;
                existingImplementation = existImpl.get();
                implementation = existingImplementation;
                // Use the existing connector bundle
                connectorBundle = existingImplementation.getConnectorBundle();
            }
        }

        // Process ApplicationOrigin - allow updating origins in all scenarios:
        // 1. Creating new application
        // 2. Creating new implementation for existing application
        // 3. Adding new version to existing implementation (can still modify application origins)
        if (dtoOrigins != null && !dtoOrigins.isEmpty()) {
            log.info("Processing {} origins from DTO (isNewApplication: {}, isNewVersion: {})",
                    dtoOrigins.size(), isNewApplication, isNewVersionOfExistingImplementation);

            // Initialize or clear the set to avoid transient entity issues
            if (application.getApplicationOrigins() == null) {
                application.setApplicationOrigins(new java.util.HashSet<>());
            } else if (isNewApplication) {
                // Clear any transient entities from DTO for new applications
                application.getApplicationOrigins().clear();
            }
            log.info("Existing origins count: {}", application.getApplicationOrigins().size());

            // Process ApplicationOrigin entities - ensure CountryOfOrigin entities exist
            for (ApplicationOrigin appOrigin : dtoOrigins) {
                if (appOrigin.getCountryOfOrigin() != null) {
                    CountryOfOrigin country = appOrigin.getCountryOfOrigin();
                    log.info("Processing country: {}", country.getName());

                    // Look up existing country by name, or create new one
                    CountryOfOrigin existingCountry = countryOfOriginRepository.findByName(country.getName())
                            .orElseGet(() -> {
                                CountryOfOrigin newCountry = new CountryOfOrigin();
                                newCountry.setName(country.getName());
                                newCountry.setDisplayName(country.getName()); // Use name as displayName if not provided
                                CountryOfOrigin saved = countryOfOriginRepository.save(newCountry);
                                log.info("Created new country: {} with ID: {}", saved.getName(), saved.getId());
                                return saved;
                            });

                    log.info("Using country: {} with ID: {}", existingCountry.getName(), existingCountry.getId());

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
                        log.info("Added new ApplicationOrigin to application");
                    } else {
                        log.info("Origin already exists, skipping");
                    }
                }
            }

            log.info("Total origins added to application: {}", application.getApplicationOrigins().size());
        }

        // Process ApplicationApplicationTag - allow updating tags in all scenarios
        if (dtoTags != null && !dtoTags.isEmpty()) {
            log.info("Processing {} tags from DTO", dtoTags.size());

            // Initialize or clear the set to avoid transient entity issues
            if (application.getApplicationApplicationTags() == null) {
                application.setApplicationApplicationTags(new java.util.HashSet<>());
            } else if (isNewApplication) {
                // Clear any transient entities from DTO for new applications
                application.getApplicationApplicationTags().clear();
            }
            log.info("Existing tags count: {}", application.getApplicationApplicationTags().size());

            // Process ApplicationApplicationTag entities - ensure ApplicationTag entities exist
            for (ApplicationApplicationTag appTag : dtoTags) {
                if (appTag.getApplicationTag() != null) {
                    ApplicationTag tag = appTag.getApplicationTag();
                    // Look up existing tag by name and tagType
                    ApplicationTag existingTag = applicationTagRepository.findByNameAndTagType(
                            tag.getName(),
                            tag.getTagType()
                    ).orElseGet(() -> {
                        ApplicationTag newTag = new ApplicationTag();
                        newTag.setName(tag.getName());
                        newTag.setTagType(tag.getTagType());
                        newTag.setDisplayName(tag.getName()); // Use name as displayName if not provided
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
                        log.info("Added new tag: {} to application", existingTag.getName());
                    } else {
                        log.info("Tag {} already exists, skipping", existingTag.getName());
                    }
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
        // Maintainer: for new implementations can be null, for new versions copy from existing
        if (isNewVersionOfExistingImplementation) {
            // Copy maintainer from existing connector bundle
            ConnectorBundle existingBundle = existingImplementation.getConnectorBundle();
            if (existingBundle != null && existingBundle.getMaintainer() != null) {
                connectorBundle.setMaintainer(existingBundle.getMaintainer());
            }
        }

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

        // Set default capabilities to GET if still null
        if (implementationVersion.getCapabilities() == null) {
            implementationVersion.setCapabilities(new ImplementationVersion.CapabilitiesType[]{
                ImplementationVersion.CapabilitiesType.GET
            });
        }

        // Clear error message - we don't want to store GitHub errors in the database
        bundleVersion.setErrorMessage(null);

        // Check framework from ConnectorBundle - only create GitHub repo for new implementations
        if (!isNewVersionOfExistingImplementation && ConnectorBundle.FrameworkType.SCIM_REST.equals(connectorBundle.getFramework())) {
            try {
                GithubClient githubClient = new GithubClient(githubProperties);
                GHRepository repository = githubClient.createProject(implementation.getDisplayName(), implementationVersion, files);

                // Store repository links in BundleVersion
                bundleVersion.setCheckoutLink(repository.getHttpTransportUrl());
                bundleVersion.setBrowseLink(repository.getHtmlUrl().toString() + "/tree/main");
            } catch (Exception e) {
                // Log the error but don't store it in the database
                log.warn("Failed to create GitHub repository: {}", e.getMessage());
                // Don't set error message - these are expected when GitHub isn't configured
            }
        }

        // Save entities in correct order to respect foreign key constraints
        log.info("Before save - Implementation description: {}", implementationVersion.getDescription());
        log.info("Before save - Application origins count: {}",
                application.getApplicationOrigins() != null ? application.getApplicationOrigins().size() : 0);

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
            Application savedApplication = applicationRepository.save(application);
            log.info("After saving application - Origins count: {}",
                    savedApplication.getApplicationOrigins() != null ? savedApplication.getApplicationOrigins().size() : 0);

            connectorBundleRepository.save(connectorBundle);
            bundleVersionRepository.save(bundleVersion);
            implementationRepository.save(implementation);
            implementationVersionRepository.save(implementationVersion);
        }

        log.info("After save - Implementation description: {}", implementationVersion.getDescription());

        // Check for errors in BundleVersion
        String errorMessage = bundleVersion.getErrorMessage();

        if (errorMessage == null) {
            try {
                // Get data from BundleVersion and ConnectorBundle for Jenkins
                String checkoutLink = bundleVersion.getCheckoutLink() != null ? bundleVersion.getCheckoutLink() : "";
                String browseLink = bundleVersion.getBrowseLink() != null ? bundleVersion.getBrowseLink() : "";
                String connectorVersion = bundleVersion.getConnectorVersion() != null ? bundleVersion.getConnectorVersion() : "";
                String framework = connectorBundle.getFramework().name();

                JenkinsClient jenkinsClient = new JenkinsClient(jenkinsProperties);
                HttpResponse<String> response = jenkinsClient.triggerJob(
                        "integration-catalog-upload-connid-connector",
                        Map.of("REPOSITORY_URL", checkoutLink,
                                "BRANCH_URL", browseLink,
                                "CONNECTOR_OID", implementationVersion.getId().toString(),
                                "IMPL_VERSION", connectorVersion,
                                "IMPL_TITLE", implementation.getDisplayName(),
                                "IMPL_FRAMEWORK", framework,
                                "SKIP_DEPLOY", "false"));
                log.info(response.body());
                return response.body();
            } catch (Exception e) {
                bundleVersion.setErrorMessage(e.getMessage());
                bundleVersionRepository.save(bundleVersion);
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

        implementationRepository.save(implementation);
        implementationVersionRepository.save(version);
    }

    public void failBuild(UUID oid, FailForm failForm) {
        ImplementationVersion version = implementationVersionRepository.getReferenceById(oid);
        version.setLifecycleState(ImplementationVersion.ImplementationVersionLifecycleType.WITH_ERROR);

        // Set error message on BundleVersion
        if (version.getBundleVersion() != null) {
            version.getBundleVersion().setErrorMessage(failForm.getErrorMessage());
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
     * @param dto RequestFormDto containing integrationApplicationName, description, capabilities, and email
     * @return The created Request entity
     */
    public Request createRequestFromForm(RequestFormDto dto) {
        String integrationApplicationName = dto.integrationApplicationName();
        String description = dto.description();
        List<String> capabilities = dto.capabilities();
        String email = dto.email();

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
            request.setRequester(email); // Email is optional, can be null

            return requestRepository.save(request);
        } catch (IllegalStateException e) {
            log.warn("Duplicate request attempt: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Failed to create request for application: {}", integrationApplicationName, e);
            throw new RuntimeException("Failed to create request: " + e.getMessage(), e);
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
                voteCount
        );
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
