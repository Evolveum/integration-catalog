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

    public ApplicationService(ApplicationRepository applicationRepository,
                              ApplicationTagRepository applicationTagRepository,
                              CountryOfOriginRepository countryOfOriginRepository,
                              ImplementationRepository implementationRepository,
                              ImplementationVersionRepository implementationVersionRepository,
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
    public String uploadConnector(UploadImplementationDto dto) {
        Application application = dto.application();
        Implementation implementation = dto.implementation();
        ImplementationVersion implementationVersion = dto.implementationVersion();
        List<ItemFile> files = dto.files();

        if (application.getId() != null) {
            Optional<Application> existApplication = applicationRepository.findById(application.getId());
            application = existApplication.orElseThrow(() -> new RuntimeException("Application not found"));
        }

        implementation.setApplication(application);
        implementationVersion.setImplementation(implementation);

        // Check framework from ConnectorBundle
        if (implementation.getConnectorBundle() != null &&
                ConnectorBundle.FrameworkType.SCIM_REST.equals(implementation.getConnectorBundle().getFramework())) {
            try {
                GithubClient githubClient = new GithubClient(githubProperties);
                GHRepository repository = githubClient.createProject(implementation.getDisplayName(), implementationVersion, files);

                // Store repository links in BundleVersion
                if (implementationVersion.getBundleVersion() != null) {
                    implementationVersion.getBundleVersion().setCheckoutLink(repository.getHttpTransportUrl());
                    implementationVersion.getBundleVersion().setBrowseLink(repository.getHtmlUrl().toString() + "/tree/main");
                }
            } catch (Exception e) {
                // Store error in BundleVersion
                if (implementationVersion.getBundleVersion() != null) {
                    implementationVersion.getBundleVersion().setErrorMessage(e.getMessage());
                }
                log.error(e.getMessage());
            }
        }

        applicationRepository.save(application);
        implementationRepository.save(implementation);
        implementationVersionRepository.save(implementationVersion);

        // Check for errors in BundleVersion
        String errorMessage = (implementationVersion.getBundleVersion() != null) ?
                implementationVersion.getBundleVersion().getErrorMessage() : null;

        if (errorMessage == null) {
            try {
                // Get data from BundleVersion and ConnectorBundle for Jenkins
                String checkoutLink = implementationVersion.getBundleVersion() != null ?
                        implementationVersion.getBundleVersion().getCheckoutLink() : "";
                String browseLink = implementationVersion.getBundleVersion() != null ?
                        implementationVersion.getBundleVersion().getBrowseLink() : "";
                String connectorVersion = implementationVersion.getBundleVersion() != null ?
                        implementationVersion.getBundleVersion().getConnectorVersion() : "";
                String framework = implementation.getConnectorBundle() != null ?
                        implementation.getConnectorBundle().getFramework().name() : "";

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
                if (implementationVersion.getBundleVersion() != null) {
                    implementationVersion.getBundleVersion().setErrorMessage(e.getMessage());
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
}
