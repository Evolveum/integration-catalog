/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.*;
import com.evolveum.midpoint.integration.catalog.mapper.ApplicationMapper;
import com.evolveum.midpoint.integration.catalog.configuration.GithubProperties;
import com.evolveum.midpoint.integration.catalog.configuration.JenkinsProperties;
import com.evolveum.midpoint.integration.catalog.form.ContinueForm;
import com.evolveum.midpoint.integration.catalog.form.FailForm;
import com.evolveum.midpoint.integration.catalog.form.SearchForm;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.evolveum.midpoint.integration.catalog.repository.*;
import com.evolveum.midpoint.integration.catalog.repository.adapter.ApplicationReadPort;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Created by Dominik.
 */
@Slf4j
@Service
public class ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final ApplicationTagRepository applicationTagRepository;
    private final CountryOfOriginRepository countryOfOriginRepository;
    private final ImplementationRepository implementationRepository;
    private final ImplementationVersionRepository implementationVersionRepository;
    private final ConnidVersionRepository connidVersionRepository;
    private final GithubProperties githubProperties;
    private final JenkinsProperties jenkinsProperties;
    private final DownloadRepository downloadRepository;
    private final RequestRepository requestRepository;
    private final VoteRepository voteRepository;
    private final ApplicationReadPort applicationReadPort;
    private final ApplicationMapper applicationMapper;
    private final BundleVersionRepository bundleVersionRepository;
    private final ConnectorBundleRepository connectorBundleRepository;
    private final ApplicationApplicationTagRepository applicationApplicationTagRepository;
    private final ApplicationTagService applicationTagService;
    private final BundleMergeService bundleMergeService;
    private final RequestVotingService requestVotingService;
    private final ConnectorDownloadService connectorDownloadService;
    private final BuildCallbackService buildCallbackService;
    private final ConnectorUploadService connectorUploadService;

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
                              ApplicationTagService applicationTagService,
                              BundleMergeService bundleMergeService,
                              RequestVotingService requestVotingService,
                              ConnectorDownloadService connectorDownloadService,
                              BuildCallbackService buildCallbackService,
                              ConnectorUploadService connectorUploadService
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
        this.applicationTagService = applicationTagService;
        this.bundleMergeService = bundleMergeService;
        this.requestVotingService = requestVotingService;
        this.connectorDownloadService = connectorDownloadService;
        this.buildCallbackService = buildCallbackService;
        this.connectorUploadService = connectorUploadService;
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

    /**
     * Uploads a connector to the integration catalog. Delegates to ConnectorUploadService.
     */
    @Transactional
    public String uploadConnector(UploadImplementationDto dto, String username) {
        return connectorUploadService.uploadConnector(dto, username);
    }

    public String downloadConnector(String connectorVersion) {
        // TODO move impl from controller
        return null;
    }

    @Transactional
    public void successBuild(UUID oid, ContinueForm continueForm) {
        buildCallbackService.successBuild(oid, continueForm);
    }

    @Transactional
    public void failBuild(UUID oid, FailForm failForm) {
        buildCallbackService.failBuild(oid, failForm);
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
        return requestVotingService.getVotes();
    }

    public List<Request> getRequests() {
        return requestVotingService.getRequests();
    }

    public void recordDownloadIfNew(ImplementationVersion version, String ip, String userAgent, OffsetDateTime cutoff) {
        connectorDownloadService.recordDownloadIfNew(version, ip, userAgent, cutoff);
    }

    /**
     * Creates a new Application and Request from the request form submission.
     * Delegates to RequestVotingService.
     */
    @Transactional
    public Request createRequestFromForm(RequestFormDto dto) {
        return requestVotingService.createRequestFromForm(dto);
    }

    public Optional<ImplementationVersion> findImplementationVersion(UUID id) {
        return implementationVersionRepository.findById(id);
    }

    public Optional<Request> getRequest(Long id) {
        return requestVotingService.getRequest(id);
    }

    public Optional<Request> getRequestForApplication(UUID appId) {
        return requestVotingService.getRequestForApplication(appId);
    }

    /**
     * Submit a vote for a request. Delegates to RequestVotingService.
     */
    public Vote submitVote(Long requestId, String voter) {
        return requestVotingService.submitVote(requestId, voter);
    }

    /**
     * Get the vote count for a specific request. Delegates to RequestVotingService.
     */
    public long getVoteCount(Long requestId) {
        return requestVotingService.getVoteCount(requestId);
    }

    /**
     * Check if a user has voted for a specific request. Delegates to RequestVotingService.
     */
    public boolean hasUserVoted(Long requestId, String voter) {
        return requestVotingService.hasUserVoted(requestId, voter);
    }

    public byte[] downloadConnector(UUID versionId, String ip, String userAgent) throws IOException {
        return connectorDownloadService.downloadConnector(versionId, ip, userAgent);
    }

    public List<ApplicationDto> getAllApplications() {
        return applicationRepository.findAll().stream()
                .map(app -> {
                    // For REQUESTED apps, get requestId and vote count
                    Long requestId = null;
                    Long voteCount = null;
                    if (app.getLifecycleState() == Application.ApplicationLifecycleType.REQUESTED) {
                        Optional<Request> request = requestVotingService.getRequestForApplication(app.getId());
                        if (request.isPresent()) {
                            requestId = request.get().getId();
                            voteCount = requestVotingService.getVoteCount(requestId);
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

    /**
     * Verify validity of the implementation version based on the data produced by the Jenkins pipeline.
     * Delegates to BundleMergeService.
     *
     * @param verifyPayload JSON form used to verify the validity of the implementation version
     * @return true if the implementation version is valid and the Jenkins pipeline can proceed
     */
    @Transactional
    public boolean verify(VerifyBundleInformationForm verifyPayload) {
        return bundleMergeService.verify(verifyPayload);
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
                            .max(ImplementationVersion.latestByPublishDate)
                            .orElse(null);

                    return applicationMapper.mapToImplementationListItemDto(impl, latestVersion);
                })
                .filter(dto -> dto != null)
                .toList();
    }
}