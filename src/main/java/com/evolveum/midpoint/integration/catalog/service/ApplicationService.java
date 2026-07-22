/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.*;
import com.evolveum.midpoint.integration.catalog.dto.EditIntegrationMethodDto;
import com.evolveum.midpoint.integration.catalog.exception.ConnectorSigningException;
import com.evolveum.midpoint.integration.catalog.mapper.ApplicationMapper;
import com.evolveum.midpoint.integration.catalog.configuration.GithubProperties;
import com.evolveum.midpoint.integration.catalog.configuration.JenkinsProperties;
import com.evolveum.midpoint.integration.catalog.form.ContinueForm;
import com.evolveum.midpoint.integration.catalog.form.FailForm;
import com.evolveum.midpoint.integration.catalog.form.SearchForm;
import com.evolveum.midpoint.integration.catalog.mapper.ConnectorMapper;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.evolveum.midpoint.integration.catalog.repository.*;
import com.evolveum.midpoint.integration.catalog.repository.adapter.ApplicationReadPort;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final ConnectorMapper connectorMapper;
    private final ConnectorBundleRepository connectorBundleRepository;
    private final ApplicationApplicationTagRepository applicationApplicationTagRepository;
    private final ApplicationTagService applicationTagService;
    private final RecentlyUsedApplicationRepository recentlyUsedApplicationRepository;
    private final BundleMergeService bundleMergeService;
    private final RequestVotingService requestVotingService;
    private final ConnectorDownloadService connectorDownloadService;
    private final BuildCallbackService buildCallbackService;
    private final ConnectorUploadService connectorUploadService;
    private final CapabilityRepository capabilityRepository;
    private final ConnectorVersionRepository connectorVersionRepository;
    private final AuthService authService;

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
                              ApplicationMapper applicationMapper, ConnectorMapper connectorMapper,
                              ApplicationApplicationTagRepository applicationApplicationTagRepository,
                              ApplicationTagService applicationTagService,
                              BundleMergeService bundleMergeService,
                              RequestVotingService requestVotingService,
                              ConnectorDownloadService connectorDownloadService,
                              BuildCallbackService buildCallbackService,
                              ConnectorUploadService connectorUploadService,
                              RecentlyUsedApplicationRepository recentlyUsedApplicationRepository,
                              CapabilityRepository capabilityRepository,
                              ConnectorVersionRepository connectorVersionRepository,
                              AuthService authService) {
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
        this.connectorMapper = connectorMapper;
        this.applicationApplicationTagRepository = applicationApplicationTagRepository;
        this.applicationTagService = applicationTagService;
        this.bundleMergeService = bundleMergeService;
        this.requestVotingService = requestVotingService;
        this.connectorDownloadService = connectorDownloadService;
        this.buildCallbackService = buildCallbackService;
        this.connectorUploadService = connectorUploadService;
        this.recentlyUsedApplicationRepository = recentlyUsedApplicationRepository;
        this.capabilityRepository = capabilityRepository;
        this.connectorVersionRepository = connectorVersionRepository;
        this.authService = authService;
    }

    /**
     * Enforces that {@code username} may modify the integration-method revision (and its
     * connectors). Throws 404 if the revision does not exist, or 403 if the caller does not
     * own it (see {@link AuthService#canEdit}). Server-side counterpart of the client's
     * edit-button gating — this is the check that actually protects the data.
     */
    private void assertCanEditMethod(String username, UUID methodId, String revision) {
        IntegrationMethod method = integrationMethodRepository.findById(new IntegrationMethodId(methodId, revision))
                .orElseThrow(() -> new RuntimeException(
                        "Integration method not found: " + methodId + "/" + revision));
        if (!authService.canEdit(username, method.getAuthor(), method.getMaintainer())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You are not allowed to modify this integration method.");
        }
        assertNotUnderReview(username, method);
    }

    /**
     * Blocks edits by non-superusers while a revision is REVIEWING: once a review starts the revision
     * is locked for its author until the review is resolved, so nothing changes underneath the
     * reviewer. Superusers are exempt — the reviewer may fix findings directly during the review
     * (or hand the revision back via stop-review for the author to fix). Mirrors the client, which
     * disables the edit controls for this state for everyone but superusers.
     */
    private void assertNotUnderReview(String username, IntegrationMethod method) {
        if (method.getLifecycleState() == LifecycleType.REVIEWING && !authService.isSuperuser(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This integration method is locked while it is under review.");
        }
    }

    /**
     * Enforces that {@code username} may edit a specific connector's content. Unlike
     * {@link #assertCanEditMethod}, this gates on the <em>connector's</em> own owner, not the
     * integration method's: a connector may be maintained by someone other than the IM
     * maintainer, in which case the IM maintainer must not be able to edit it (only its
     * maintainer, or a superuser, may). Throws 404 if the method/connector is missing, 403 otherwise.
     */
    private void assertCanEditConnector(String username, UUID methodId, String revision, Integer connectorId) {
        IntegrationMethod method = integrationMethodRepository.findById(new IntegrationMethodId(methodId, revision))
                .orElseThrow(() -> new RuntimeException(
                        "Integration method not found: " + methodId + "/" + revision));
        Connector connector = method.getConnectors().stream()
                .map(IntegrationMethodConnector::getConnector)
                .filter(Objects::nonNull)
                .filter(c -> connectorId.equals(c.getId()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException(
                        "Connector " + connectorId + " is not linked to integration method " + methodId + "/" + revision));
        if (!authService.canEdit(username, connector.getAuthor(), connector.getMaintainer())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You are not allowed to modify this connector.");
        }
        assertNotUnderReview(username, method);
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
                .map(v -> new MidpointVersionDto(v.getId(), v.getVersion(), v.getVersionName(), v.isCurrent()))
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

    public boolean checkBundleNameExists(String bundleName) {
        if (bundleName == null || bundleName.isBlank()) return false;
        return connectorBundleRepository.existsByBundleName(bundleName);
    }

    /**
     * Whether the given connector version already exists in the catalog on another connector with
     * the same identity (bundle name + class name). Duplicate versions are never blocked — the
     * connector version must match the Maven artifact, so it is the reviewer's call; this check
     * only feeds the warning telling them a matching version exists and should be reused.
     */
    public boolean checkConnectorVersionExists(String bundleName, String className, String version,
                                               Integer excludeConnectorId) {
        if (bundleName == null || bundleName.isBlank() || version == null || version.isBlank()) return false;
        String normalizedClassName = (className == null || className.isBlank()) ? null : className.trim();
        return connectorVersionRepository.existsDuplicateVersion(
                bundleName.trim(), normalizedClassName, version.trim(), excludeConnectorId);
    }

    public List<CapabilityDto> getCapabilities() {
        return capabilityRepository.findAll().stream()
                .sorted(java.util.Comparator.comparingInt(c -> c.getDisplayOrder() != null ? c.getDisplayOrder() : 0))
                .map(c -> new CapabilityDto(c.getName(), c.getGlobality(), c.getDisplayOrder()))
                .toList();
    }

    @Transactional
    public String uploadConnector(UploadImplementationDto dto, String username) {
        return connectorUploadService.uploadConnector(dto, username);
    }

    @Transactional
    public String editIntegrationMethod(UUID methodId, String currentRevision, EditIntegrationMethodDto dto,
                                        String username) {
        assertCanEditMethod(username, methodId, currentRevision);
        return connectorUploadService.editIntegrationMethod(methodId, currentRevision, dto);
    }

    @Transactional
    public void startReviewIntegrationMethod(UUID methodId, String revision, String username) {
        // Starting a review is a superuser-only action, mirroring approve/reject (the client already
        // restricts it to superusers; this is the server-side enforcement).
        if (!authService.isSuperuser(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a superuser may start a review of an integration method.");
        }
        connectorUploadService.startReviewIntegrationMethod(methodId, revision, username);
    }

    @Transactional
    public void stopReviewIntegrationMethod(UUID methodId, String revision, String username) {
        // Stopping a review is a superuser-only action, mirroring start-review.
        if (!authService.isSuperuser(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a superuser may stop a review of an integration method.");
        }
        connectorUploadService.stopReviewIntegrationMethod(methodId, revision, username);
    }

    @Transactional
    public void publishIntegrationMethod(UUID methodId, String revision, String username) {
        // Approving a revision is a superuser-only action (the client already restricts it to
        // superusers; this is the server-side enforcement).
        if (!authService.isSuperuser(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a superuser may publish an integration method.");
        }
        connectorUploadService.publishIntegrationMethod(methodId, revision, username);
    }

    @Transactional
    public void rejectIntegrationMethod(UUID methodId, String revision, String username) {
        if (!authService.isSuperuser(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a superuser may reject an integration method.");
        }
        connectorUploadService.rejectIntegrationMethod(methodId, revision, username);
    }

    @Transactional
    public String addConnectorToIntegrationMethod(UUID appId, UUID methodId, String revision,
                                                AddConnectorDto dto, String username) {
        assertCanEditMethod(username, methodId, revision);
        return connectorUploadService.addConnectorToIntegrationMethod(appId, methodId, revision, dto, username);
    }

    @Transactional
    public void updateConnector(UUID methodId, String revision, Integer connectorId, EditConnectorDto dto,
                                String username) {
        // A connector is gated on its own maintainer, not the IM's: the IM maintainer must not
        // be able to edit a connector maintained by someone else (a superuser still can).
        assertCanEditConnector(username, methodId, revision, connectorId);
        connectorUploadService.updateConnector(methodId, revision, connectorId, dto);
    }

    @Transactional
    public void deleteConnectorFromIntegrationMethod(UUID methodId, String revision, Integer connectorId,
                                                     String username) {
        assertCanEditMethod(username, methodId, revision);
        connectorUploadService.deleteConnectorFromIntegrationMethod(methodId, revision, connectorId);
    }

    @Transactional
    public void updateConnectorCompatibility(UUID methodId, String revision, Integer connectorId,
                                             String connectorVersionFrom, String connectorVersionTo,
                                             String username) {
        assertCanEditMethod(username, methodId, revision);
        connectorUploadService.updateConnectorCompatibility(methodId, revision, connectorId,
                connectorVersionFrom, connectorVersionTo);
    }

    @Transactional(readOnly = true)
    public List<ImplementationListItemDto> getConnectorsForIntegrationMethod(UUID methodId, String revision) {
        return integrationMethodRepository.findById(new IntegrationMethodId(methodId, revision))
                .map(applicationMapper::mapConnectorsForMethod)
                .orElseGet(List::of);
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

    public void recordMethodDownload(UUID methodId, String revision, String ip, String userAgent) {
        connectorDownloadService.recordMethodDownload(methodId, revision, ip, userAgent);
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


    public AllowedConnectorsListDto listActiveConnectors() {
        List<SignedActiveConnectorDto> list = connectorVersionRepository.findByLifecycleState(LifecycleType.ACTIVE).stream()
                .map(connectorVersion -> {
                    try {
                        return connectorMapper.toActiveConnectorDto(connectorVersion);
                    } catch (Exception e) {
                        throw new ConnectorSigningException("Failed to sign connector data", e);
                    }
                })
                .toList();

        return new AllowedConnectorsListDto(
                new SignedActiveConnectorsListDto(
                        "Connectors from Integration catalog",
                        list));
    }

    @Transactional(readOnly = true)
    public List<CatalogConnectorDto> listCatalogConnectors() {
        Set<Integer> activeConnectorIds = integrationMethodRepository.findByLifecycleState(LifecycleType.ACTIVE).stream()
                .flatMap(m -> m.getConnectors().stream())
                .map(IntegrationMethodConnector::getConnector)
                .filter(Objects::nonNull)
                .map(Connector::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return connectorBundleRepository.findByLifecycleState(LifecycleType.ACTIVE).stream()
                .flatMap(bundle -> {
                    ConnectorBundleVersion latest = bundle.getBundleVersions().stream()
                            .max(java.util.Comparator.comparingInt(ConnectorBundleVersion::getId))
                            .orElse(null);
                    return bundle.getConnectors().stream()
                            .filter(c -> activeConnectorIds.contains(c.getId()))
                            .map(connector -> new CatalogConnectorDto(
                                    bundle.getId(),
                                    connector.getDisplayName(),
                                    connector.getDescription(),
                                    connector.getRevision(),
                                    bundle.getDisplayName(),
                                    connector.getMaintainer(),
                                    bundle.getLicense() != null ? bundle.getLicense().name() : null,
                                    bundle.getBuildFramework() != null ? bundle.getBuildFramework().name() : null,
                                    bundle.getFramework() != null ? bundle.getFramework().name() : null,
                                    latest != null ? latest.getBrowseLink() : null,
                                    latest != null ? latest.getGitCloneUrl() : bundle.getGitCloneUrl(),
                                    latest != null ? latest.getPathToProject() : bundle.getPathToProject(),
                                    connector.getFullyQualifiedClassName(),
                                    applicationMapper.mapLatestPublishedConnectorVersionCapabilities(connector)
                            ));
                })
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
