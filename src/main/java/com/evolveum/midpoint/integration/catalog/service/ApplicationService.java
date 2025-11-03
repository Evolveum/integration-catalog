/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.ApplicationDto;
import com.evolveum.midpoint.integration.catalog.dto.ApplicationCardDto;
import com.evolveum.midpoint.integration.catalog.dto.ApplicationTagDto;
import com.evolveum.midpoint.integration.catalog.dto.CategoryCountDto;
import com.evolveum.midpoint.integration.catalog.dto.CountryOfOriginDto;
import com.evolveum.midpoint.integration.catalog.dto.ImplementationVersionDto;
import com.evolveum.midpoint.integration.catalog.common.ItemFile;
import com.evolveum.midpoint.integration.catalog.integration.GithubClient;
import com.evolveum.midpoint.integration.catalog.integration.JenkinsClient;
import com.evolveum.midpoint.integration.catalog.configuration.GithubProperties;
import com.evolveum.midpoint.integration.catalog.configuration.JenkinsProperties;
import com.evolveum.midpoint.integration.catalog.form.ContinueForm;
import com.evolveum.midpoint.integration.catalog.form.FailForm;
import com.evolveum.midpoint.integration.catalog.form.SearchForm;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.evolveum.midpoint.integration.catalog.repository.*;
import com.evolveum.midpoint.integration.catalog.utils.ApplicationReadPort;

import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHRepository;
import com.evolveum.midpoint.integration.catalog.utils.InetAddress;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

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
                              ApplicationReadPort applicationReadPort
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
    }

    public Application getApplication(UUID uuid) {
        return applicationRepository.getReferenceById(uuid);
    }

    public ImplementationVersion getImplementationVersion(UUID uuid) {
        return implementationVersionRepository.getReferenceById(uuid);
    }

    public ConnidVersion getConnectorVersion(UUID id) {
        ImplementationVersion implVersion = implementationVersionRepository.getReferenceById(id);
        BundleVersion bundleVersion = implVersion.getBundleVersion();
        return connidVersionRepository.getReferenceById(bundleVersion.getConnidVersion());
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

    public List<CategoryCountDto> getCommonTagCounts() {
        List<ApplicationTag> commonTags = applicationTagRepository.findByTagType(ApplicationTag.ApplicationTagType.COMMON);

        // Filter for certification levels only
        List<String> certificationLevelNames = List.of("verified_by_evolveum", "community_contributed", "experimental");

        List<CategoryCountDto> commonTagCounts = commonTags.stream()
                .filter(tag -> certificationLevelNames.contains(tag.getName().toLowerCase().replace(" ", "_").replace("-", "_")))
                .map(tag -> new CategoryCountDto(
                        tag.getDisplayName(),
                        (long) tag.getApplicationApplicationTags().size()
                ))
                .toList();

        return commonTagCounts;
    }

    public List<CategoryCountDto> getAppStatusCounts() {
        List<ApplicationTag> commonTags = applicationTagRepository.findByTagType(ApplicationTag.ApplicationTagType.COMMON);

        // Filter for app status only
        List<String> appStatusNames = List.of("available", "requested_by_community", "pending");

        List<CategoryCountDto> appStatusCounts = commonTags.stream()
                .filter(tag -> appStatusNames.contains(tag.getName().toLowerCase().replace(" ", "_").replace("-", "_")))
                .map(tag -> new CategoryCountDto(
                        tag.getDisplayName(),
                        (long) tag.getApplicationApplicationTags().size()
                ))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));

        // Add "All" with total application count at the beginning
        long totalCount = applicationRepository.count();
        appStatusCounts.add(0, new CategoryCountDto("All", totalCount));

        return appStatusCounts;
    }

    public List<CategoryCountDto> getSupportedOperationsCounts() {
        List<ApplicationTag> commonTags = applicationTagRepository.findByTagType(ApplicationTag.ApplicationTagType.COMMON);

        // Filter for supported operations only
        List<String> supportedOpsNames = List.of("search", "modify_delete", "bulk_action");

        List<CategoryCountDto> supportedOpsCounts = commonTags.stream()
                .filter(tag -> supportedOpsNames.contains(tag.getName().toLowerCase().replace(" ", "_").replace("-", "_").replace("/", "_").replace(" ", "")))
                .map(tag -> new CategoryCountDto(
                        tag.getDisplayName(),
                        (long) tag.getApplicationApplicationTags().size()
                ))
                .toList();

        return supportedOpsCounts;
    }

    public List<CountryOfOrigin> getCountriesOfOrigin() {
        return countryOfOriginRepository.findAll();
    }

    /**
     * Method upload connector to integration catalog and return link to git repository at the successful processing and failure message at a processing failure.
     * The connector is stored on GitHub in case there is no GitHub repositor of the connector and then upload to nexus with a use jenkins job.
     * @param application
     * @param implementation
     * @param connectorBundle
     * @param bundleVersion
     * @param implementationVersion
     * @param files
     * @return
     */
    public String uploadConnector(
            Application application,
            Implementation implementation,
            ConnectorBundle connectorBundle,
            BundleVersion bundleVersion,
            ImplementationVersion implementationVersion,
            List<ItemFile> files
    ) {
        if (application.getId() != null) {
            Optional<Application> existApplication = applicationRepository.findById(application.getId());
            application = existApplication.orElseThrow(() -> new RuntimeException("Application not found"));
        }

        // Set up relationships
        implementation.setApplication(application);
        implementation.setConnectorBundle(connectorBundle);
        implementationVersion.setImplementation(implementation);
        implementationVersion.setBundleVersion(bundleVersion);

        // If SCIM_REST framework, create GitHub repository
        if (ConnectorBundle.FrameworkType.SCIM_REST.equals(connectorBundle.getFramework())) {
            try {
                GithubClient githubClient = new GithubClient(githubProperties);
                GHRepository repository = githubClient.createProject(implementation.getDisplayName(), implementationVersion, files);
                bundleVersion.setCheckoutLink(repository.getHttpTransportUrl());
                bundleVersion.setBrowseLink(repository.getHtmlUrl().toString() + "/tree/main");
            } catch (Exception e) {
                bundleVersion.setErrorMessage(e.getMessage());
                log.error(e.getMessage());
            }
        }

        // Save entities in correct order
        applicationRepository.save(application);
        connectorBundleRepository.save(connectorBundle);
        bundleVersionRepository.save(bundleVersion);
        implementationRepository.save(implementation);
        implementationVersionRepository.save(implementationVersion);

        // If no error from GitHub, trigger Jenkins job
        if (bundleVersion.getErrorMessage() == null) {
            try {
                JenkinsClient jenkinsClient = new JenkinsClient(jenkinsProperties);
                HttpResponse<String> response = jenkinsClient.triggerJob(
                        "integration-catalog-upload-connid-connector",
                        Map.of("REPOSITORY_URL", bundleVersion.getCheckoutLink() != null ? bundleVersion.getCheckoutLink() : "",
                                "BRANCH_URL", bundleVersion.getBrowseLink() != null ? bundleVersion.getBrowseLink() : "",
                                "CONNECTOR_OID", implementationVersion.getId().toString(),
                                "IMPL_VERSION", bundleVersion.getConnectorVersion() != null ? bundleVersion.getConnectorVersion() : "",
                                "IMPL_TITLE", implementation.getDisplayName() != null ? implementation.getDisplayName() : "",
                                "IMPL_FRAMEWORK", connectorBundle.getFramework().name(),
                                "SKIP_DEPLOY", "false"));
                log.info(response.body());
                return response.body();
            } catch (Exception e) {
                bundleVersion.setErrorMessage(e.getMessage());
                bundleVersionRepository.save(bundleVersion);
                log.error(e.getMessage());
            }
        }

        return bundleVersion.getErrorMessage();
    }

    /**
     * Get download link by connector version string.
     * Note: This method is not currently used by any endpoint.
     * @param connectorVersion The connector version to search for
     * @return The download link URL, or null if not found
     */
    public String downloadConnector(String connectorVersion) {
        if (connectorVersion == null) {
            return null;
        }

        // Search BundleVersion by connectorVersion (this field is now in BundleVersion, not ImplementationVersion)
        List<BundleVersion> bundleVersions = bundleVersionRepository.findAll((root, query, cb) ->
                cb.equal(root.get("connectorVersion"), connectorVersion));

        if (bundleVersions.isEmpty()) {
            return null;
        }

        // Return the download link from the first matching BundleVersion
        // Note: If connector_version is not unique, this will only return the first match
        return bundleVersions.getFirst().getDownloadLink();
    }

    public void successBuild(UUID oid, ContinueForm continueForm) {
        // Get ImplementationVersion by oid
        ImplementationVersion version = implementationVersionRepository.getReferenceById(oid);

        // Get BundleVersion and update its fields
        BundleVersion bundleVersion = version.getBundleVersion();
        if (bundleVersion == null) {
            throw new IllegalStateException("BundleVersion not found for ImplementationVersion: " + oid);
        }

        bundleVersion.setConnectorVersion(continueForm.getConnectorVersion())
                .setDownloadLink(continueForm.getDownloadLink());

        // Update ImplementationVersion
        OffsetDateTime odt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(continueForm.getPublishTime()), ZoneOffset.UTC);
        version.setPublishDate(odt)
                .setLifecycleState(ImplementationVersion.ImplementationVersionLifecycleType.ACTIVE);

        // Get Implementation and update ConnectorBundle if needed
        Implementation implementation = version.getImplementation();
        if (continueForm.getConnectorBundle() != null && !continueForm.getConnectorBundle().isEmpty()) {
            // Look up or create ConnectorBundle by bundleName
            ConnectorBundle connectorBundle = implementation.getConnectorBundle();
            if (connectorBundle != null && !continueForm.getConnectorBundle().equals(connectorBundle.getBundleName())) {
                // Bundle name changed - look up by new name
                List<ConnectorBundle> bundles = connectorBundleRepository.findAll((root, query, cb) ->
                        cb.equal(root.get("bundleName"), continueForm.getConnectorBundle()));
                if (!bundles.isEmpty()) {
                    implementation.setConnectorBundle(bundles.getFirst());
                }
            }
        }

        // Save entities
        bundleVersionRepository.save(bundleVersion);
        implementationRepository.save(implementation);
        implementationVersionRepository.save(version);
    }

    public void failBuild(UUID oid, FailForm failForm) {
        ImplementationVersion version = implementationVersionRepository.getReferenceById(oid);
        version.setLifecycleState(ImplementationVersion.ImplementationVersionLifecycleType.WITH_ERROR);

        // Get BundleVersion and update errorMessage
        BundleVersion bundleVersion = version.getBundleVersion();
        if (bundleVersion != null) {
            bundleVersion.setErrorMessage(failForm.getErrorMessage());
            bundleVersionRepository.save(bundleVersion);
        }

        Implementation implementation = version.getImplementation();
        Application application = implementation.getApplication();

        if (implementation.getImplementationVersions().size() == 1
                && application.getImplementations().size() == 1) {
            application.setLifecycleState(Application.ApplicationLifecycleType.WITH_ERROR);
            applicationRepository.save(application);
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

    public Page<ImplementationVersion> searchVersionsOfConnector(SearchForm searchForm, int page, int size) {
        Specification<ImplementationVersion> spec = (root, query, cb) -> cb.conjunction();

        // Search by maintainer (now in ConnectorBundle via Implementation)
        if (searchForm.getMaintainer() != null && !searchForm.getMaintainer().isBlank()) {
            spec = spec.and((root, query, cb) -> {
                // Join path: ImplementationVersion -> Implementation -> ConnectorBundle
                var implementationJoin = root.join("implementation");
                var connectorBundleJoin = implementationJoin.join("connectorBundle");
                return cb.like(cb.lower(connectorBundleJoin.get("maintainer")), "%" + searchForm.getMaintainer().toLowerCase() + "%");
            });
        }

        // Search by lifecycle state
        if (searchForm.getLifecycleState() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("lifecycleState"), searchForm.getLifecycleState()));
        }

        // Search by application ID
        if (searchForm.getApplicationId() != null) {
            spec = spec.and((root, query, cb) -> {
                var implementationJoin = root.join("implementation");
                var applicationJoin = implementationJoin.join("application");
                return cb.equal(applicationJoin.get("id"), searchForm.getApplicationId());
            });
        }

        // Search by system version
        if (searchForm.getSystemVersion() != null && !searchForm.getSystemVersion().isBlank()) {
            spec = spec.and((root, query, cb) ->
                    cb.like(cb.lower(root.get("systemVersion")), "%" + searchForm.getSystemVersion().toLowerCase() + "%"));
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
        // Use capabilities JSON field instead
        r.setRequester(requester);
        return requestRepository.save(r);
    }

    /**
     * Creates a new Application and Request from the request form submission.
     * The Application will be created with lifecycle state REQUESTED.
     *
     * @param integrationApplicationName The display name of the application
     * @param description The application description
     * @param capabilities List of capabilities to be stored as JSON
     * @param email Optional email address to be stored as requester
     * @return The created Request entity
     */
    public Request createRequestFromForm(String integrationApplicationName, String description, List<String> capabilities, String email) {
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
            application.setRiskLevel("UNKNOWN"); // Default risk level for requested applications
            application.setLifecycleState(Application.ApplicationLifecycleType.REQUESTED);

            // Save the application (UUID is auto-generated, timestamps are auto-set)
            application = applicationRepository.save(application);

            // Convert capabilities list to JSON string
            String capabilitiesJson = null;
            if (capabilities != null && !capabilities.isEmpty()) {
                capabilitiesJson = "[" + capabilities.stream()
                        .map(cap -> "\"" + cap.replace("\"", "\\\"") + "\"") // Escape quotes
                        .reduce((a, b) -> a + "," + b)
                        .orElse("") + "]";
            }

            // Create the Request entity
            Request request = new Request();
            request.setApplication(application);
            request.setCapabilities(capabilitiesJson);
            request.setRequester(email); // Email is optional, can be null

            return requestRepository.save(request);
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

    public List<Request> getRequestsForApplication(UUID appId) {
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

        // downloadLink is now in BundleVersion
        BundleVersion bundleVersion = version.getBundleVersion();
        if (bundleVersion == null || bundleVersion.getDownloadLink() == null) {
            throw new IllegalArgumentException("Download link not available for version: " + versionId);
        }

        try (InputStream in = new URL(bundleVersion.getDownloadLink()).openStream()) {
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
                    String lifecycleState = app.getLifecycleState() != null ? app.getLifecycleState().name() : null;

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

                    List<ApplicationTagDto> categories = filterTagsByType(app, ApplicationTag.ApplicationTagType.CATEGORY);
                    List<ApplicationTagDto> tags = mapAllTags(app);
                    List<ImplementationVersionDto> implementationVersions = null;
                    try {
                        implementationVersions = mapImplementationVersions(app);
                    } catch (Exception e) {
                        e.printStackTrace();
                        // If implementation versions fail to load, continue without them
                    }

                    // For REQUESTED apps, get requestId and vote count
                    Long requestId = null;
                    Long voteCount = null;
                    if (app.getLifecycleState() == Application.ApplicationLifecycleType.REQUESTED) {
                        List<Request> requests = getRequestsForApplication(app.getId());
                        if (!requests.isEmpty()) {
                            requestId = requests.get(0).getId();
                            voteCount = getVoteCount(requestId);
                        }
                    }

                    return new ApplicationDto(
                            app.getId(),
                            app.getDisplayName(),
                            app.getDescription(),
                            app.getLogo(),
                            app.getRiskLevel(),
                            lifecycleState,
                            app.getLastModified(),
                            app.getCreatedAt(),
                            null, // capabilities - not needed for list view
                            null, // requester - not needed for list view
                            origins,
                            categories,
                            tags,
                            implementationVersions,
                            requestId,
                            voteCount);
                })
                .toList();
    }

    private List<ApplicationTagDto> filterTagsByType(Application app, ApplicationTag.ApplicationTagType tagType) {
        if (app.getApplicationApplicationTags() == null) {
            return null;
        }
        return app.getApplicationApplicationTags().stream()
                .filter(appTag -> appTag.getApplicationTag().getTagType() == tagType)
                .map(this::mapToApplicationTagDto)
                .toList();
    }

    private List<ApplicationTagDto> mapAllTags(Application app) {
        if (app.getApplicationApplicationTags() == null) {
            return null;
        }
        return app.getApplicationApplicationTags().stream()
                .map(this::mapToApplicationTagDto)
                .toList();
    }

    private ApplicationTagDto mapToApplicationTagDto(ApplicationApplicationTag appTag) {
        return new ApplicationTagDto(
                appTag.getApplicationTag().getId(),
                appTag.getApplicationTag().getName(),
                appTag.getApplicationTag().getDisplayName(),
                appTag.getApplicationTag().getTagType() != null ? appTag.getApplicationTag().getTagType().name() : null
        );
    }

    private List<ImplementationVersionDto> mapImplementationVersions(Application app) {
        // Updated for new schema: Application -> Implementation -> ImplementationVersion -> BundleVersion
        // ConnectorBundle is separate (for bundle metadata only)
        if (app.getImplementations() == null) {
            return null;
        }

        return app.getImplementations().stream()
                .flatMap(impl -> {
                    if (impl.getImplementationVersions() == null) {
                        return Stream.empty();
                    }

                    return impl.getImplementationVersions().stream().map(version -> {
                        List<String> implementationTags = null;
                        if (impl.getImplementationImplementationTags() != null) {
                            implementationTags = impl.getImplementationImplementationTags().stream()
                                    .map(tag -> tag.getImplementationTag().getDisplayName())
                                    .toList();
                        }
                        List<String> capabilities = parseCapabilitiesJson(version.getCapabilitiesJson());
                        String lifecycleState = version.getLifecycleState() != null ? version.getLifecycleState().name() : null;

                        // Get version-specific data from BundleVersion
                        BundleVersion bundleVersion = version.getBundleVersion();
                        String connectorVersion = bundleVersion != null ? bundleVersion.getConnectorVersion() : null;
                        LocalDate releasedDate = bundleVersion != null ? bundleVersion.getReleasedDate() : null;
                        String downloadLink = bundleVersion != null ? bundleVersion.getDownloadLink() : null;

                        return new ImplementationVersionDto(
                                version.getDescription(),
                                implementationTags,
                                capabilities,
                                connectorVersion,      // from BundleVersion
                                version.getSystemVersion(),
                                releasedDate,          // from BundleVersion
                                version.getAuthor(),
                                lifecycleState,
                                downloadLink           // from BundleVersion
                        );
                    });
                })
                .toList();
    }

    private List<String> parseCapabilitiesJson(String capabilitiesJson) {
        if (capabilitiesJson == null || capabilitiesJson.isEmpty()) {
            return null;
        }
        try {
            // Remove brackets and quotes, split by comma
            String cleaned = capabilitiesJson.replace("[", "").replace("]", "").replace("\"", "");
            if (cleaned.isEmpty()) {
                return null;
            }
            return List.of(cleaned.split(",\\s*"));
        } catch (Exception e) {
            return null;
        }
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
        return new ApplicationCardDto(
                app.getId(),
                app.getDisplayName(),
                app.getDescription(),
                app.getLogo(),
                app.getRiskLevel(),
                lifecycleState
        );
    }
}
