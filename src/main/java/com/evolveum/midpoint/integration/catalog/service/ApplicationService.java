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
import java.util.List;
import java.util.Objects;
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
    private final IntegrationMethodRepository integrationMethodRepository;
    private final IntegrationMethodTypeRepository integrationMethodTypeRepository;
    private final MidpointVersionRepository midpointVersionRepository;
    private final ConnectorBundleVersionRepository connectorBundleVersionRepository;
    private final GithubProperties githubProperties;
    private final JenkinsProperties jenkinsProperties;
    private final DownloadRepository downloadRepository;
    private final RequestRepository requestRepository;
    private final VoteRepository voteRepository;
    private final ApplicationReadPort applicationReadPort;
    private final ApplicationMapper applicationMapper;
    private final ConnectorBundleRepository connectorBundleRepository;
    private final ApplicationApplicationTagRepository applicationApplicationTagRepository;
    private final ApplicationTagService applicationTagService;
    private final RecentlyUsedApplicationRepository recentlyUsedApplicationRepository;
    private final BundleMergeService bundleMergeService;
    private final RequestVotingService requestVotingService;
    private final ConnectorDownloadService connectorDownloadService;
    private final BuildCallbackService buildCallbackService;
    private final ConnectorUploadService connectorUploadService;

    public ApplicationService(ApplicationRepository applicationRepository,
                              ApplicationTagRepository applicationTagRepository,
                              CountryOfOriginRepository countryOfOriginRepository,
                              IntegrationMethodRepository integrationMethodRepository,
                              IntegrationMethodTypeRepository integrationMethodTypeRepository,
                              MidpointVersionRepository midpointVersionRepository,
                              ConnectorBundleRepository connectorBundleRepository,
                              ConnectorBundleVersionRepository connectorBundleVersionRepository,
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
                              ConnectorUploadService connectorUploadService,
                              RecentlyUsedApplicationRepository recentlyUsedApplicationRepository) {
        this.applicationRepository = applicationRepository;
        this.applicationTagRepository = applicationTagRepository;
        this.countryOfOriginRepository = countryOfOriginRepository;
        this.integrationMethodRepository = integrationMethodRepository;
        this.integrationMethodTypeRepository = integrationMethodTypeRepository;
        this.midpointVersionRepository = midpointVersionRepository;
        this.connectorBundleVersionRepository = connectorBundleVersionRepository;
        this.githubProperties = githubProperties;
        this.jenkinsProperties = jenkinsProperties;
        this.downloadRepository = downloadRepository;
        this.requestRepository = requestRepository;
        this.voteRepository = voteRepository;
        this.applicationReadPort = applicationReadPort;
        this.applicationMapper = applicationMapper;
        this.connectorBundleRepository = connectorBundleRepository;
        this.applicationApplicationTagRepository = applicationApplicationTagRepository;
        this.applicationTagService = applicationTagService;
        this.bundleMergeService = bundleMergeService;
        this.requestVotingService = requestVotingService;
        this.connectorDownloadService = connectorDownloadService;
        this.buildCallbackService = buildCallbackService;
        this.connectorUploadService = connectorUploadService;
        this.recentlyUsedApplicationRepository = recentlyUsedApplicationRepository;
    }

    public Application getApplication(UUID uuid) {
        return applicationRepository.findById(uuid)
                .orElseThrow(() -> new RuntimeException("Application not found with id: " + uuid));
    }

    public List<ApplicationTagDto> getApplicationTags() {
        return applicationTagRepository.findAll().stream()
                .map(tag -> new ApplicationTagDto(
                        tag.getId(),
                        tag.getName(),
                        tag.getDisplayName(),
                        tag.getTagType() != null ? tag.getTagType().name() : null
                ))
                .toList();
    }

    public List<IntegrationMethodType> getIntegrationMethodTypes() {
        return integrationMethodTypeRepository.findAll();
    }

    public List<MidpointVersionDto> getMidpointVersions() {
        return midpointVersionRepository.findAll().stream()
                .map(v -> new MidpointVersionDto(v.getId(), v.getVersion(), v.getVersionName()))
                .toList();
    }

    public List<CategoryCountDto> getCategoryCounts() {
        return applicationTagRepository.findByTagType(ApplicationTag.ApplicationTagType.CATEGORY).stream()
                .map(tag -> new CategoryCountDto(
                        tag.getDisplayName(),
                        (long) tag.getApplicationApplicationTags().size()))
                .toList();
    }

    public List<CountryOfOrigin> getCountriesOfOrigin() {
        return countryOfOriginRepository.findAll();
    }

    public boolean checkVersionExists(String bundleVersion) {
        if (bundleVersion == null || bundleVersion.isEmpty()) return false;
        return connectorBundleVersionRepository.existsByBundleVersion(bundleVersion);
    }

    @Transactional
    public String uploadConnector(UploadImplementationDto dto, String username) {
        return connectorUploadService.uploadConnector(dto, username);
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
                    cb.equal(root.get("lifecycleState"), searchForm.getLifecycleState()));
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by("id").ascending());
        return applicationRepository.findAll(spec, pageable);
    }

    public Page<IntegrationMethod> searchIntegrationMethods(SearchForm searchForm, int page, int size) {
        Specification<IntegrationMethod> spec = (root, query, cb) -> cb.conjunction();

        if (searchForm.getMaintainer() != null && !searchForm.getMaintainer().isBlank()) {
            spec = spec.and((root, query, cb) ->
                    cb.like(cb.lower(root.get("maintainer")), "%" + searchForm.getMaintainer().toLowerCase() + "%"));
        }

        if (searchForm.getLifecycleState() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("lifecycleState"), searchForm.getLifecycleState()));
        }

        if (searchForm.getApplicationId() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("application").get("id"), searchForm.getApplicationId()));
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by("id").ascending());
        return integrationMethodRepository.findAll(spec, pageable);
    }

    public List<Vote> getVotes() {
        return requestVotingService.getVotes();
    }

    public List<Request> getRequests() {
        return requestVotingService.getRequests();
    }

    @Transactional
    public Request createRequestFromForm(RequestFormDto dto) {
        return requestVotingService.createRequestFromForm(dto);
    }

    public Optional<Request> getRequest(Long id) {
        return requestVotingService.getRequest(id);
    }

    public Optional<Request> getRequestForApplication(UUID appId) {
        return requestVotingService.getRequestForApplication(appId);
    }

    public Vote submitVote(Long requestId, String voter) {
        return requestVotingService.submitVote(requestId, voter);
    }

    public long getVoteCount(Long requestId) {
        return requestVotingService.getVoteCount(requestId);
    }

    public boolean hasUserVoted(Long requestId, String voter) {
        return requestVotingService.hasUserVoted(requestId, voter);
    }

    public void cancelRequest(Long requestId) {
        requestVotingService.cancelRequest(requestId);
    }

    public byte[] downloadConnector(UUID integMethodId, String ip, String userAgent) throws IOException {
        return connectorDownloadService.downloadConnector(integMethodId, ip, userAgent);
    }

    public List<ApplicationDto> getAllApplications() {
        return applicationRepository.findAll().stream()
                .map(app -> {
                    Long requestId = null;
                    Long voteCount = null;
                    if (app.getLifecycleState() == Application.ApplicationLifecycleType.REQUESTED) {
                        Optional<Request> request = requestVotingService.getRequestForApplication(app.getId());
                        if (request.isPresent()) {
                            requestId = request.get().getId();
                            voteCount = requestVotingService.getVoteCount(requestId);
                        }
                    }
                    return applicationMapper.mapToApplicationDto(app, null, null, requestId, voteCount);
                })
                .toList();
    }

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

    public List<ActiveConnectorDto> listActiveConnectors() {
        return applicationReadPort.findByLifecycleState(Application.ApplicationLifecycleType.ACTIVE).stream()
                .map(applicationMapper::toActiveConnectorDto)
                .toList();
    }

    @Transactional
    public boolean verify(VerifyBundleInformationForm verifyPayload) {
        return bundleMergeService.verify(verifyPayload);
    }

    @Transactional(readOnly = true)
    public List<ImplementationListItemDto> getIntegrationMethodsByApplicationId(UUID applicationId) {
        return integrationMethodRepository.findByApplicationId(applicationId).stream()
                .map(applicationMapper::mapToIntegrationMethodListItemDto)
                .filter(dto -> dto != null)
                .toList();
    }

    public List<ApplicationDto> getRecentlyUsedApplications() {
        return recentlyUsedApplicationRepository.findAllByOrderByIdDesc().stream()
                .map(RecentlyUsedApplication::getApplicationId)
                .distinct()
                .limit(9)
                .map(id -> applicationRepository.findById(id).orElse(null))
                .filter(app -> app != null)
                .map(app -> applicationMapper.mapToApplicationDto(app, null, null, null, null))
                .toList();
    }

    @Transactional
    public void recordRecentlyUsed(UUID applicationId, String userId) {
        recentlyUsedApplicationRepository.deleteByUserIdAndApplicationId(userId, applicationId);
        recentlyUsedApplicationRepository.flush();
        RecentlyUsedApplication entry = new RecentlyUsedApplication()
                .setUserId(userId)
                .setApplicationId(applicationId);
        recentlyUsedApplicationRepository.save(entry);
    }

    public long getTotalDownloadsCount() {
        return downloadRepository.count();
    }

    public long countDownloadsForApplication(UUID applicationId) {
        return applicationRepository.findById(applicationId)
                .map(app -> {
                    if (app.getIntegrationMethods() == null) return 0L;
                    return app.getIntegrationMethods().stream()
                            .flatMap(m -> m.getConnectors().stream())
                            .map(IntegrationMethodConnector::getConnector)
                            .filter(Objects::nonNull)
                            .flatMap(c -> c.getConnectorVersions().stream())
                            .map(ConnectorVersion::getConnectorBundleVersion)
                            .filter(Objects::nonNull)
                            .distinct()
                            .mapToLong(downloadRepository::countByConnectorBundleVersion)
                            .sum();
                })
                .orElse(0L);
    }
}
