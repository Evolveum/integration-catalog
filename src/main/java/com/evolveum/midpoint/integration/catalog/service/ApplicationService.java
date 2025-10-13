/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.ApplicationDto;
import com.evolveum.midpoint.integration.catalog.dto.ApplicationTagDto;
import com.evolveum.midpoint.integration.catalog.dto.CountryOfOriginDto;
import com.evolveum.midpoint.integration.catalog.dto.ImplementationVersionDto;
import com.evolveum.midpoint.integration.catalog.integration.GithubClient;
import com.evolveum.midpoint.integration.catalog.integration.JenkinsClient;
import com.evolveum.midpoint.integration.catalog.configuration.GithubProperties;
import com.evolveum.midpoint.integration.catalog.configuration.JenkinsProperties;
import com.evolveum.midpoint.integration.catalog.form.ContinueForm;
import com.evolveum.midpoint.integration.catalog.form.FailForm;
import com.evolveum.midpoint.integration.catalog.form.ItemFile;
import com.evolveum.midpoint.integration.catalog.form.SearchForm;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.evolveum.midpoint.integration.catalog.repository.*;

import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHRepository;
import com.evolveum.midpoint.integration.catalog.utils.Inet;
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
import java.util.stream.Stream;

/**
 * Created by Dominik.
 */
@Slf4j
@Service
public class ApplicationService {

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
    private final DownloadsRepository downloadsRepository;

    @Autowired
    private final RequestRepository requestRepository;

    @Autowired
    private final VotesRepository votesRepository;

    public ApplicationService(ApplicationRepository applicationRepository,
                              ApplicationTagRepository applicationTagRepository,
                              CountryOfOriginRepository countryOfOriginRepository,
                              ImplementationRepository implementationRepository,
                              ImplementationVersionRepository implementationVersionRepository,
                              ConnidVersionRepository connidVersionRepository,
                              GithubProperties githubProperties,
                              JenkinsProperties jenkinsProperties,
                              DownloadsRepository downloadsRepository,
                              RequestRepository requestRepository,
                              VotesRepository votesRepository
    ) {
        this.applicationRepository = applicationRepository;
        this.applicationTagRepository = applicationTagRepository;
        this.countryOfOriginRepository = countryOfOriginRepository;
        this.implementationRepository = implementationRepository;
        this.implementationVersionRepository = implementationVersionRepository;
        this.connidVersionRepository = connidVersionRepository;
        this.githubProperties = githubProperties;
        this.jenkinsProperties = jenkinsProperties;
        this.downloadsRepository = downloadsRepository;
        this.requestRepository = requestRepository;
        this.votesRepository = votesRepository;
    }

    public Application getApplication(UUID uuid) {
        return applicationRepository.findById(uuid)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + uuid));
    }

    public ImplementationVersion getImplementationVersion(UUID uuid) {
        return implementationVersionRepository.getReferenceById(uuid);
    }

    public ConnidVersion getConnectorVersion(UUID id) {
        return connidVersionRepository.getReferenceById(
                this.implementationVersionRepository.getReferenceById(id).getConnectorVersion());
    }

    public List<ApplicationTag> getApplicationTags() {
        return applicationTagRepository.findAll();
    }

    public List<CountryOfOrigin> getCountriesOfOrigin() {
        return countryOfOriginRepository.findAll();
    }

    /**
     * Method upload connector to integration catalog and return link to git repository at the successful processing and failure message at a processing failure.
     * The connector is stored on GitHub in case there is no GitHub repositor of the connector and then upload to nexus with a use jenkins job.
     * @param application
     * @param implementation
     * @param implementationVersion
     * @param files
     * @return
     */
    public String uploadConnector(
            Application application,
            Implementation implementation,
            ImplementationVersion implementationVersion,
            List<ItemFile> files
    ) {
        implementation.setApplication(application);
        implementationVersion.setImplementation(implementation);

        try {
            if (Implementation.FrameworkType.SCIM_REST.equals(implementation.getFramework())) {
                GithubClient githubClient = new GithubClient(githubProperties);
                GHRepository repository = githubClient.createProject(implementation.getDisplayName(), implementationVersion, files);
                implementationVersion.setCheckoutLink(repository.getHtmlUrl().toString());
            }

            JenkinsClient jenkinsClient = new JenkinsClient(jenkinsProperties);
            HttpResponse<String> response = jenkinsClient.triggerJob(
                    "integration-catalog-upload-connid-connector",
                    Map.of("REPOSITORY_URL", implementationVersion.getCheckoutLink(),
                            "BRANCH_URL", implementationVersion.getBrowseLink(),
                            "CONNECTOR_OID", implementationVersion.getId().toString(),
                            "IMPL_FRAMEWORK", implementation.getFramework().name()));

            log.info(response.body());
        } catch (Exception e) {
            implementationVersion.setErrorMessage(e.getMessage());
            log.error(e.getMessage());
            return e.getMessage();
        } finally {
            applicationRepository.save(application);
            implementationRepository.save(implementation);
            implementationVersionRepository.save(implementationVersion);
        }

        return implementationVersion.getCheckoutLink();
    }

    public String downloadConnector(String connectorVersion) {
        // TODO move impl from controller
        return null;
    }

    public void successBuild(UUID oid, ContinueForm continueForm) {
        ImplementationVersion version = implementationVersionRepository.getReferenceById(oid);
        Implementation implementation = version.getImplementation();
        implementation.setConnectorBundle(continueForm.getConnectorBundle());

        OffsetDateTime odt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(continueForm.getPublishTime()), ZoneOffset.UTC);
        version.setConnectorVersion(continueForm.getConnectorVersion())
                .setDownloadLink(continueForm.getDownloadLink())
                .setPublishDate(odt)
                .setLifecycleState(ImplementationVersion.ImplementationVersionLifecycleType.ACTIVE);

        implementationRepository.save(implementation);
        implementationVersionRepository.save(version);
    }

    public void failBuild(UUID oid, FailForm failForm) {
        ImplementationVersion version = implementationVersionRepository.getReferenceById(oid);
        version.setLifecycleState(ImplementationVersion.ImplementationVersionLifecycleType.WITH_ERROR)
                .setErrorMessage(failForm.getErrorMessage());

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

    public List<Votes> getVotes() {
        return votesRepository.findAll();
    }

    public List<Request> getRequests() {
        return requestRepository.findAll();
    }

    public void recordDownloadIfNew(ImplementationVersion version, Inet ip, String userAgent, OffsetDateTime cutoff) {
        // boolean duplicate = downloadsRepository
        //         .existsRecentDuplicate(version.getId(), ip, userAgent, cutoff);

        // if (!duplicate) {
            Downloads dl = new Downloads();
            dl.setImplementationVersion(version);
            dl.setIpAddress(ip);
            dl.setUserAgent(userAgent);
            dl.setDownloadedAt(OffsetDateTime.now());
            downloadsRepository.save(dl);
        // }
    }

    public Request createRequest(UUID applicationId, String capabilitiesType, String requester) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));

        Request.CapabilitiesType ct;
        try {
            ct = Request.CapabilitiesType.valueOf(capabilitiesType.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid capabilitiesType: " + capabilitiesType +
                    " (allowed: READ, CREATE, MODIFY, DELETE)");
        }

        Request r = new Request();
        r.setApplication(application);
        r.setCapabilitiesType(ct);
        r.setRequester(requester);
        return requestRepository.save(r);
    }

    public Optional<ImplementationVersion> findImplementationVersion(UUID id) {
        return implementationVersionRepository.findById(id);
    }

    public Optional<Request> getRequest(Long id) {
        return requestRepository.findById(id);
    }

    public List<Request> getRequestsForApplication(UUID appId) {
        return requestRepository.findByApplication_Id(appId);
    }

    public byte[] downloadConnector(UUID versionId, String ip, String userAgent, long offsetSeconds) {
        ImplementationVersion version = implementationVersionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + versionId));

        try (InputStream in = new URL(version.getDownloadLink()).openStream()) {
            byte[] fileBytes = in.readAllBytes();

            Inet inet = new Inet(ip);
            OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(offsetSeconds);
            recordDownloadIfNew(version, inet, userAgent, cutoff);

            return fileBytes;
        } catch (IOException e) {
            throw new RuntimeException("Failed to download connector: " + e.getMessage(), e);
        }
    }

    public List<ApplicationDto> getAllApplications() {
        return applicationRepository.findAll().stream()
                .map(app -> {
                    String lifecycleState = null;
                    try {
                        lifecycleState = app.getLifecycleState() != null ? app.getLifecycleState().name() : null;
                    } catch (Exception e) {
                        // Handle empty string or invalid enum values - PostgreSQL problem
                        lifecycleState = null;
                    }

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

                    return new ApplicationDto(
                            app.getId(),
                            app.getDisplayName(),
                            app.getDescription(),
                            app.getLogo(),
                            null, // riskLevel - not yet implemented
                            lifecycleState,
                            app.getLastModified(),
                            origins,
                            categories,
                            tags,
                            implementationVersions);
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
        if (app.getImplementations() == null) {
            return null;
        }
        return app.getImplementations().stream()
                .flatMap(impl -> impl.getImplementationVersions() != null ?
                        impl.getImplementationVersions().stream().map(version -> {
                            List<String> implementationTags = null;
                            if (impl.getImplementationImplementationTags() != null) {
                                implementationTags = impl.getImplementationImplementationTags().stream()
                                        .map(tag -> tag.getImplementationTag().getDisplayName())
                                        .toList();
                            }
                            List<String> capabilities = parseCapabilitiesJson(version.getCapabilitiesJson());
                            String lifecycleState = version.getLifecycleState() != null ? version.getLifecycleState().name() : null;
                            return new ImplementationVersionDto(version.getDescription(), implementationTags, capabilities, version.getConnectorVersion(), version.getSystemVersion(), version.getReleasedDate(), version.getAuthor(), lifecycleState);
                        }) : Stream.empty())
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
}
