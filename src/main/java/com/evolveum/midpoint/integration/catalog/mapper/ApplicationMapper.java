/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.mapper;

import com.evolveum.midpoint.integration.catalog.dto.ActiveConnectorDto;
import com.evolveum.midpoint.integration.catalog.dto.ApplicationCardDto;
import com.evolveum.midpoint.integration.catalog.dto.ApplicationDto;
import com.evolveum.midpoint.integration.catalog.dto.ApplicationTagDto;
import com.evolveum.midpoint.integration.catalog.dto.CountryOfOriginDto;
import com.evolveum.midpoint.integration.catalog.dto.ImplementationListItemDto;
import com.evolveum.midpoint.integration.catalog.dto.ImplementationVersionDto;
import com.evolveum.midpoint.integration.catalog.object.Application;
import com.evolveum.midpoint.integration.catalog.object.ApplicationApplicationTag;
import com.evolveum.midpoint.integration.catalog.object.ApplicationTag;
import com.evolveum.midpoint.integration.catalog.object.BundleVersion;
import com.evolveum.midpoint.integration.catalog.object.ConnectorBundle;
import com.evolveum.midpoint.integration.catalog.object.Implementation;
import com.evolveum.midpoint.integration.catalog.object.ImplementationVersion;
import com.evolveum.midpoint.integration.catalog.object.Request;
import com.evolveum.midpoint.integration.catalog.repository.DownloadRepository;
import com.evolveum.midpoint.integration.catalog.repository.RequestRepository;
import com.evolveum.midpoint.integration.catalog.repository.VoteRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Mapper class for converting Application entities to DTOs
 */
@Component
public class ApplicationMapper {

    private final RequestRepository requestRepository;
    private final VoteRepository voteRepository;
    private final DownloadRepository downloadRepository;

    public ApplicationMapper(RequestRepository requestRepository, VoteRepository voteRepository, DownloadRepository downloadRepository) {
        this.requestRepository = requestRepository;
        this.voteRepository = voteRepository;
        this.downloadRepository = downloadRepository;
    }

    /**
     * Filters application tags by type and maps to DTOs
     * @param app Application entity
     * @param tagType Tag type to filter by
     * @return List of ApplicationTagDto or null if no tags
     */
    public List<ApplicationTagDto> filterTagsByType(Application app, ApplicationTag.ApplicationTagType tagType) {
        if (app.getApplicationApplicationTags() == null) {
            return null;
        }
        return app.getApplicationApplicationTags().stream()
                .filter(appTag -> appTag.getApplicationTag().getTagType() == tagType)
                .map(this::mapToApplicationTagDto)
                .toList();
    }

    /**
     * Maps all application tags to DTOs
     * @param app Application entity
     * @return List of ApplicationTagDto or null if no tags
     */
    public List<ApplicationTagDto> mapAllTags(Application app) {
        if (app.getApplicationApplicationTags() == null) {
            return null;
        }
        return app.getApplicationApplicationTags().stream()
                .map(this::mapToApplicationTagDto)
                .toList();
    }

    /**
     * Converts ApplicationApplicationTag to ApplicationTagDto
     * @param appTag ApplicationApplicationTag entity
     * @return ApplicationTagDto
     */
    public ApplicationTagDto mapToApplicationTagDto(ApplicationApplicationTag appTag) {
        return new ApplicationTagDto(
                appTag.getApplicationTag().getId(),
                appTag.getApplicationTag().getName(),
                appTag.getApplicationTag().getDisplayName(),
                appTag.getApplicationTag().getTagType() != null ? appTag.getApplicationTag().getTagType().name() : null
        );
    }

    /**
     * Maps implementation versions from Application to DTOs
     * @param app Application entity
     * @return List of ImplementationVersionDto or null if no implementations
     */
    public List<ImplementationVersionDto> mapImplementationVersions(Application app) {
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
                            List<String> capabilities = convertCapabilitiesToList(version.getCapabilities());
                            String lifecycleState = version.getLifecycleState() != null ? version.getLifecycleState().name() : null;

                            // Get data from BundleVersion
                            String connectorVersion = null;
                            java.time.LocalDate releasedDate = null;
                            String downloadLink = null;
                            String framework = null;
                            String midpointVersion = null;
                            if (version.getBundleVersion() != null) {
                                connectorVersion = version.getBundleVersion().getConnectorVersion();
                                releasedDate = version.getBundleVersion().getReleasedDate();
                                downloadLink = version.getBundleVersion().getDownloadLink();
                                if (version.getBundleVersion().getConnectorBundle() != null
                                        && version.getBundleVersion().getConnectorBundle().getFramework() != null) {
                                    framework = version.getBundleVersion().getConnectorBundle().getFramework().name();
                                }
                                if (version.getBundleVersion().getConnidVersionObject() != null) {
                                    midpointVersion = version.getBundleVersion().getConnidVersionObject().getMidpointVersion();
                                }
                            }

                            // Get download count for this version
                            Long downloadCount = downloadRepository.countByImplementationVersionId(version.getId());

                            return new ImplementationVersionDto(
                                    version.getId(),
                                    version.getDescription(),
                                    implementationTags,
                                    capabilities,
                                    connectorVersion,
                                    version.getSystemVersion(),
                                    releasedDate,
                                    version.getAuthor(),
                                    lifecycleState,
                                    downloadLink,
                                    framework,
                                    version.getErrorMessage(),
                                    downloadCount,
                                    midpointVersion
                            );
                        }) : Stream.empty())
                .toList();
    }

    /**
     * Converts capabilities enum array to list of strings
     * @param capabilities CapabilitiesType array
     * @return List of capability strings or null if empty
     */
    private List<String> convertCapabilitiesToList(ImplementationVersion.CapabilitiesType[] capabilities) {
        if (capabilities == null || capabilities.length == 0) {
            return null;
        }
        return Stream.of(capabilities)
                .map(Enum::name)
                .toList();
    }

    /**
     * Extracts unique frameworks from application's implementations
     * @param app Application entity
     * @return List of unique framework names (e.g., "JAVA_BASED", "LOW_CODE") or null if no implementations
     */
    public List<String> extractFrameworks(Application app) {
        if (app.getImplementations() == null || app.getImplementations().isEmpty()) {
            return null;
        }
        return app.getImplementations().stream()
                .map(Implementation::getConnectorBundle)
                .filter(bundle -> bundle != null && bundle.getFramework() != null)
                .map(bundle -> bundle.getFramework().name())
                .distinct()
                .toList();
    }

    /**
     * Maps Application entity to ApplicationDto with request data fetched automatically
     * @param app Application entity
     * @return ApplicationDto
     */
    public ApplicationDto mapToApplicationDto(Application app) {
        // Fetch Request data if application is REQUESTED
        List<String> capabilities = null;
        String requester = null;
        Long requestId = null;
        Long voteCount = null;

        if (app.getLifecycleState() == Application.ApplicationLifecycleType.REQUESTED) {
            Optional<Request> requestOpt = requestRepository.findByApplicationId(app.getId());
            if (requestOpt.isPresent()) {
                Request request = requestOpt.get();
                capabilities = request.getObjectClassCapabilities().stream()
                        .filter(occ -> occ.getCapabilities() != null)
                        .flatMap(occ -> Arrays.stream(occ.getCapabilities()))
                        .map(Enum::name)
                        .distinct()
                        .collect(java.util.stream.Collectors.toList());
                requester = request.getRequester();
                requestId = request.getId();
                voteCount = voteRepository.countByRequestId(requestId);
            }
        }

        return mapToApplicationDto(app, capabilities, requester, requestId, voteCount);
    }

    /**
     * Maps Application entity to ApplicationDto with all related data
     * @param app Application entity
     * @param capabilities Request capabilities (null for list view)
     * @param requester Request requester (null for list view)
     * @param requestId Request ID (null if not requested)
     * @param voteCount Vote count (null if not requested)
     * @return ApplicationDto
     */
    public ApplicationDto mapToApplicationDto(Application app,
                                               List<String> capabilities,
                                               String requester,
                                               Long requestId,
                                               Long voteCount) {
        // Map origins
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

        // Map categories, tags, and implementation versions
        List<ApplicationTagDto> categories = filterTagsByType(app, ApplicationTag.ApplicationTagType.CATEGORY);
        List<ApplicationTagDto> tags = mapAllTags(app);
        List<ImplementationVersionDto> implementationVersions = mapImplementationVersions(app);

        // Extract frameworks from implementations
        List<String> frameworks = extractFrameworks(app);

        // Convert lifecycle state
        String lifecycleState = app.getLifecycleState() != null ? app.getLifecycleState().name() : null;

        // Build and return DTO
        return ApplicationDto.builder()
                .id(app.getId())
                .displayName(app.getDisplayName())
                .description(app.getDescription())
                .logoPath(app.getLogoPath())
                .logoContentType(app.getLogoContentType())
                .logoOriginalName(app.getLogoOriginalName())
                .logoSizeBytes(app.getLogoSizeBytes())
                .lifecycleState(lifecycleState)
                .lastModified(app.getLastModified())
                .createdAt(app.getCreatedAt())
                .capabilities(capabilities)
                .requester(requester)
                .origins(origins)
                .categories(categories)
                .tags(tags)
                .implementationVersions(implementationVersions)
                .requestId(requestId)
                .voteCount(voteCount)
                .frameworks(frameworks)
                .build();
    }

    /**
     * Convert Application entity to ApplicationCardDto for list display
     * @param app Application entity
     * @return ApplicationCardDto
     */
    public ApplicationCardDto toCardDto(Application app) {
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

        // Get request info and capabilities if lifecycle state is REQUESTED
        Long requestId = null;
        Long voteCount = null;
        List<String> capabilities = new ArrayList<>();
        if (app.getLifecycleState() == Application.ApplicationLifecycleType.REQUESTED) {
            Optional<Request> requestOpt = requestRepository.findByApplicationId(app.getId());
            if (requestOpt.isPresent()) {
                Request request = requestOpt.get();
                requestId = request.getId();
                voteCount = voteRepository.countByRequestId(request.getId());
                // Get capabilities from request object class entries
                request.getObjectClassCapabilities().stream()
                        .filter(occ -> occ.getCapabilities() != null)
                        .flatMap(occ -> Arrays.stream(occ.getCapabilities()))
                        .map(Enum::name)
                        .forEach(capabilities::add);
            }
        }

        // Aggregate capabilities from all implementations
        if (app.getImplementations() != null) {
            app.getImplementations().stream()
                    .filter(impl -> impl.getImplementationVersions() != null)
                    .flatMap(impl -> impl.getImplementationVersions().stream())
                    .filter(version -> version.getCapabilities() != null)
                    .flatMap(version -> Arrays.stream(version.getCapabilities()))
                    .map(Enum::name)
                    .distinct()
                    .forEach(capabilities::add);
        }

        // Deduplicate capabilities
        capabilities = capabilities.stream().distinct().toList();

        // Extract frameworks from implementations
        List<String> frameworks = extractFrameworks(app);

        // Extract unique midpoint versions from all implementations
        List<String> midpointVersions = new ArrayList<>();
        if (app.getImplementations() != null) {
            app.getImplementations().stream()
                    .filter(impl -> impl.getImplementationVersions() != null)
                    .flatMap(impl -> impl.getImplementationVersions().stream())
                    .filter(version -> version.getBundleVersion() != null
                            && version.getBundleVersion().getConnidVersionObject() != null
                            && version.getBundleVersion().getConnidVersionObject().getMidpointVersion() != null)
                    .map(version -> version.getBundleVersion().getConnidVersionObject().getMidpointVersion())
                    .distinct()
                    .sorted()
                    .forEach(midpointVersions::add);
        }

        return new ApplicationCardDto(
                app.getId(),
                app.getDisplayName(),
                app.getDescription(),
                app.getLogoPath(),
                app.getLogoContentType(),
                app.getLogoOriginalName(),
                app.getLogoSizeBytes(),
                lifecycleState,
                origins,
                categories,
                tags,
                capabilities.isEmpty() ? null : capabilities,
                requestId,
                voteCount,
                frameworks,
                midpointVersions.isEmpty() ? null : midpointVersions
        );
    }

    /**
     * Convert Application entity to ActiveConnectorDto for the active connectors endpoint.
     * @param app Application entity
     * @return ActiveConnectorDto
     */
    public ActiveConnectorDto toActiveConnectorDto(Application app) {
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

        // Aggregate capabilities from all implementations
        List<String> capabilities = new ArrayList<>();
        if (app.getImplementations() != null) {
            app.getImplementations().stream()
                    .filter(impl -> impl.getImplementationVersions() != null)
                    .flatMap(impl -> impl.getImplementationVersions().stream())
                    .filter(version -> version.getCapabilities() != null)
                    .flatMap(version -> Arrays.stream(version.getCapabilities()))
                    .map(Enum::name)
                    .distinct()
                    .forEach(capabilities::add);
        }

        // Extract frameworks from implementations
        List<String> frameworks = extractFrameworks(app);

        // Extract unique midpoint versions from all implementations
        List<String> midpointVersions = new ArrayList<>();
        if (app.getImplementations() != null) {
            app.getImplementations().stream()
                    .filter(impl -> impl.getImplementationVersions() != null)
                    .flatMap(impl -> impl.getImplementationVersions().stream())
                    .filter(version -> version.getBundleVersion() != null
                            && version.getBundleVersion().getConnidVersionObject() != null
                            && version.getBundleVersion().getConnidVersionObject().getMidpointVersion() != null)
                    .map(version -> version.getBundleVersion().getConnidVersionObject().getMidpointVersion())
                    .distinct()
                    .sorted()
                    .forEach(midpointVersions::add);
        }

        return new ActiveConnectorDto(
                app.getId(),
                app.getDisplayName(),
                app.getDescription(),
                origins,
                categories,
                tags,
                capabilities.isEmpty() ? null : capabilities,
                frameworks,
                midpointVersions.isEmpty() ? null : midpointVersions
        );
    }

    /**
     * Maps Implementation with its latest version to ImplementationListItemDto
     * @param impl Implementation entity
     * @param latestVersion Latest ImplementationVersion for this implementation
     * @return ImplementationListItemDto
     */
    public ImplementationListItemDto mapToImplementationListItemDto(Implementation impl, ImplementationVersion latestVersion) {
        ConnectorBundle bundle = impl.getConnectorBundle();
        BundleVersion bundleVersion = latestVersion.getBundleVersion();

        // Format publish date as DD.MM.YYYY
        String publishedDate = latestVersion.getPublishDate() != null
                ? latestVersion.getPublishDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                : "";

        return new ImplementationListItemDto(
                impl.getId(),
                impl.getDisplayName(),                                    // name (for list display)
                latestVersion.getDescription(),                          // description (for list display)
                publishedDate,                                           // publishedDate
                bundleVersion.getConnectorVersion(),                     // version
                impl.getDisplayName(),                                   // displayName
                bundle.getMaintainer(),                                  // maintainer
                bundle.getLicense().name(),                              // licenseType
                latestVersion.getDescription(),                          // implementationDescription
                bundleVersion.getBrowseLink(),                           // browseLink
                bundle.getTicketingSystemLink(),                         // ticketingLink
                bundleVersion.getBuildFramework().name(),                // buildFramework
                bundleVersion.getCheckoutLink(),                         // checkoutLink
                bundleVersion.getPathToProject(),                         // pathToProjectDirectory
                latestVersion.getClassName()
        );
    }
}
