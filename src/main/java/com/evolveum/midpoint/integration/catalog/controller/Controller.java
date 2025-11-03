/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.controller;

import com.evolveum.midpoint.integration.catalog.dto.UploadImplementationDto;
import com.evolveum.midpoint.integration.catalog.dto.ApplicationDto;
import com.evolveum.midpoint.integration.catalog.dto.ApplicationTagDto;
import com.evolveum.midpoint.integration.catalog.dto.CategoryCountDto;
import com.evolveum.midpoint.integration.catalog.dto.CountryOfOriginDto;
import com.evolveum.midpoint.integration.catalog.dto.ImplementationVersionDto;
import com.evolveum.midpoint.integration.catalog.dto.RequestFormDto;
import com.evolveum.midpoint.integration.catalog.form.ContinueForm;
import com.evolveum.midpoint.integration.catalog.form.FailForm;
import com.evolveum.midpoint.integration.catalog.form.SearchForm;
import com.evolveum.midpoint.integration.catalog.object.*;

import com.evolveum.midpoint.integration.catalog.repository.ImplementationVersionRepository;
import com.evolveum.midpoint.integration.catalog.repository.RequestRepository;
import com.evolveum.midpoint.integration.catalog.service.ApplicationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api")
@Tag(name = "Integration catalog", description = "API for managing endpoints of Integration catalog")
public class Controller {

    private final ApplicationService applicationService;

    private final ImplementationVersionRepository implementationVersionRepository;
    private final RequestRepository requestRepository;

    public Controller(ApplicationService applicationService, ImplementationVersionRepository implementationVersionRepository, RequestRepository requestRepository) {
        this.applicationService = applicationService;
        this.implementationVersionRepository = implementationVersionRepository;
        this.requestRepository = requestRepository;
    }

    @Operation(summary = "Get application by ID",
            description = "Fetches a single application by its UUID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Application found"),
            @ApiResponse(responseCode = "404", description = "Application not found")
    })
    @GetMapping("/applications/{id}")
    public ResponseEntity<ApplicationDto> getApplication(@PathVariable UUID id) {
        try {
            Application app = applicationService.getApplication(id);
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
                System.out.println("DEBUG: app.getImplementations() = " + app.getImplementations());
                if (app.getImplementations() != null) {
                    System.out.println("DEBUG: implementations count = " + app.getImplementations().size());
                }
                implementationVersions = mapImplementationVersions(app);
                System.out.println("DEBUG: implementationVersions result = " + implementationVersions);
            } catch (Exception e) {
                System.err.println("ERROR: Exception in mapImplementationVersions");
                e.printStackTrace();
                // If implementation versions fail to load, continue without them
            }

            // Fetch Request data if application is REQUESTED
            List<String> capabilities = null;
            String requester = null;
            Long requestId = null;
            Long voteCount = null;
            if (app.getLifecycleState() == Application.ApplicationLifecycleType.REQUESTED) {
                List<Request> requests = applicationService.getRequestsForApplication(app.getId());
                if (!requests.isEmpty()) {
                    Request request = requests.get(0); // Get first request
                    capabilities = parseCapabilitiesJson(request.getCapabilities());
                    requester = request.getRequester();
                    requestId = request.getId();
                    voteCount = applicationService.getVoteCount(requestId);
                }
            }

            ApplicationDto dto = new ApplicationDto(
                    app.getId(),
                    app.getDisplayName(),
                    app.getDescription(),
                    app.getLogo(),
                    app.getRiskLevel(),
                    lifecycleState,
                    app.getLastModified(),
                    app.getCreatedAt(),
                    capabilities,
                    requester,
                    origins,
                    categories,
                    tags,
                    implementationVersions,
                    requestId,
                    voteCount
            );
            return ResponseEntity.ok(dto);
        } catch (RuntimeException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Get connector version by ID",
            description = "Fetches a connector version by its UUID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Connector version found"),
            @ApiResponse(responseCode = "404", description = "Connector version not found")
    })
    @GetMapping("/connector-version/{id}")
    public ResponseEntity<ConnidVersion> getConnectorVersion(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(applicationService.getConnectorVersion(id));
        } catch (RuntimeException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Get all application tags",
            description = "Fetches a application tags")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Application tags found"),
            @ApiResponse(responseCode = "404", description = "Application tags not found")
    })
    @GetMapping("/application-tags")
    public ResponseEntity<List<ApplicationTag>> getApplicationTags() {
        try {
            return ResponseEntity.ok(applicationService.getApplicationTags());
        } catch (RuntimeException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Get all countries of origin",
            description = "Fetches a countries of origin")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Countries of origin found"),
            @ApiResponse(responseCode = "404", description = "Countries of origin not found")
    })
    @GetMapping("/countries-of-origin")
    public ResponseEntity<List<CountryOfOrigin>> getCountriesOfOrigin() {
        try {
            return ResponseEntity.ok(applicationService.getCountriesOfOrigin());
        } catch (RuntimeException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "", description = "")
    @PostMapping("/upload/connector")
    public ResponseEntity<String> uploadConnector(@RequestBody UploadImplementationDto dto) {
        try {
            return ResponseEntity.status(HttpStatus.OK).body(applicationService.uploadConnector(
                    dto.application(),
                    dto.implementation(),
                    dto.connectorBundle(),
                    dto.bundleVersion(),
                    dto.implementationVersion(),
                    dto.files()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @Operation(summary = "Upload status - success",
            description = "")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Upload status - success worked"),
            @ApiResponse(responseCode = "404", description = "Upload status - success did not work")
    })
    @PostMapping("/upload/continue/{oid}")
    public ResponseEntity completeBuildSuccessfully(@RequestBody ContinueForm continueForm, @PathVariable UUID oid) {
        applicationService.successBuild(oid, continueForm);
        return ResponseEntity.ok(HttpStatus.OK);
    }

    @Operation(summary = "Upload status - fail",
            description = "")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Upload status - failed"),
            @ApiResponse(responseCode = "404", description = "Upload status - did not fail")
    })
    @PostMapping("/upload/continue/fail/{oid}")
    public ResponseEntity completeBuildWithFailure(@RequestBody FailForm failForm, @PathVariable UUID oid) {
        applicationService.failBuild(oid, failForm);
        return ResponseEntity.ok(HttpStatus.OK);
    }

    @Operation(summary = "Download based on ID",
            description = "")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Download based on ID worked"),
            @ApiResponse(responseCode = "404", description = "Download based on ID failed")
    })
    @GetMapping("/download/{oid}")
    public ResponseEntity<byte[]> downloadConnector(@PathVariable UUID oid, HttpServletRequest request) {

        ImplementationVersion version = applicationService.findImplementationVersion(oid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Version not found"));

        // downloadLink is now in BundleVersion
        BundleVersion bundleVersion = version.getBundleVersion();
        if (bundleVersion == null || bundleVersion.getDownloadLink() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Download link not available");
        }

        String filename = bundleVersion.getDownloadLink().substring(bundleVersion.getDownloadLink().lastIndexOf('/') + 1);
        String ip = request.getRemoteAddr();
        String ua = request.getHeader("User-Agent");

        try {
            byte[] fileBytes = applicationService.downloadConnector(oid, ip, ua);
            if (fileBytes == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to download connector: no data returned");
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDisposition(org.springframework.http.ContentDisposition.builder("attachment")
                    .filename(filename)
                    .build());
            headers.setContentLength(fileBytes.length);
            return new ResponseEntity<>(fileBytes, headers, HttpStatus.OK);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to download connector: " + e.getMessage(), e);
        }
    }

    @Operation(summary = "Search applications by parameters",
            description = "")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Applications found"),
            @ApiResponse(responseCode = "404", description = "Applications not found")
    })
    @PostMapping("/applications/search/{size}/{page}")
    public ResponseEntity<Page<Application>> searchApplication(
            @RequestBody SearchForm searchForm,
            @PathVariable int size,
            @PathVariable int page
    ) {
        try {
            return ResponseEntity.ok(applicationService.searchApplication(searchForm, page, size));
        } catch (RuntimeException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Search versions of connector by parameters",
            description = "")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Versions of connector found"),
            @ApiResponse(responseCode = "404", description = "Versions of connector not found")
    })
    @GetMapping("/version-of-connector/search/{size}/{page}")
    public ResponseEntity<Page<ImplementationVersion>> searchVersionsOfConnector(
            @RequestBody SearchForm searchForm,
            @PathVariable int size,
            @PathVariable int page
    ) {
        try {
            return ResponseEntity.ok(applicationService.searchVersionsOfConnector(searchForm, page, size));
        } catch (RuntimeException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Get Request ID",
            description = "")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Request ID found"),
            @ApiResponse(responseCode = "404", description = "Request ID not found")
    })
    @GetMapping("/requests/{id}")
    public ResponseEntity<Request> getRequest(@PathVariable Long id) {
        return applicationService.getRequest(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
    }

    @Operation(summary = "Get Requests for Application ID",
            description = "")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Requests for Application ID found"),
            @ApiResponse(responseCode = "404", description = "Requests for Application ID not found")
    })
    @GetMapping("/applications/{appId}/requests")
    public List<Request> getRequestsForApplication(@PathVariable UUID appId) {
        return applicationService.getRequestsForApplication(appId);
    }

    @Operation(summary = "Create Request from Form",
            description = "Creates a new Application with lifecycle state REQUESTED and associated Request from the request form submission")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Request created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request data")
    })
    @PostMapping("/requests")
    public ResponseEntity<Request> createRequest(@Valid @RequestBody RequestFormDto dto) {
        try {
            Request created = applicationService.createRequestFromForm(
                    dto.integrationApplicationName(),
                    dto.description(),
                    dto.capabilities(),
                    dto.email());
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create request: " + ex.getMessage());
        }
    }

    @Operation(summary = "Submit vote for request",
            description = "Allows a logged-in user to vote for a request. Each user can only vote once per request.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Vote submitted successfully"),
            @ApiResponse(responseCode = "400", description = "User already voted or request not found")
    })
    @PostMapping("/requests/{requestId}/vote")
    public ResponseEntity<Vote> submitVote(@PathVariable Long requestId, @RequestParam String voter) {
        try {
            Vote vote = applicationService.submitVote(requestId, voter);
            return ResponseEntity.status(HttpStatus.CREATED).body(vote);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @Operation(summary = "Get vote count for request",
            description = "Returns the total number of votes for a specific request")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Vote count retrieved successfully")
    })
    @GetMapping("/requests/{requestId}/votes/count")
    public ResponseEntity<Long> getVoteCount(@PathVariable Long requestId) {
        long count = applicationService.getVoteCount(requestId);
        return ResponseEntity.ok(count);
    }

    @Operation(summary = "Check if user has voted",
            description = "Checks if a specific user has already voted for a request")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Check completed successfully")
    })
    @GetMapping("/requests/{requestId}/votes/check")
    public ResponseEntity<Boolean> hasUserVoted(@PathVariable Long requestId, @RequestParam String voter) {
        boolean hasVoted = applicationService.hasUserVoted(requestId, voter);
        return ResponseEntity.ok(hasVoted);
    }

    @Operation(summary = "Show counts of categories",
            description = "")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Showed counts of categories successfully"),
            @ApiResponse(responseCode = "404", description = "Show counts of categories failed")
    })
    @GetMapping("/categories/counts")
    public ResponseEntity<List<CategoryCountDto>> getCategoryCounts() {
        return ResponseEntity.ok(applicationService.getCategoryCounts());
    }

    @Operation(summary = "Show counts of common tags",
            description = "")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Showed counts of common tags successfully"),
            @ApiResponse(responseCode = "404", description = "Show counts of common tags failed")
    })
    @GetMapping("/common-tags/counts")
    public ResponseEntity<List<CategoryCountDto>> getCommonTagCounts() {
        return ResponseEntity.ok(applicationService.getCommonTagCounts());
    }

    @Operation(summary = "Show counts of app status",
            description = "")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Showed counts of app status successfully"),
            @ApiResponse(responseCode = "404", description = "Show counts of app status failed")
    })
    @GetMapping("/app-status/counts")
    public ResponseEntity<List<CategoryCountDto>> getAppStatusCounts() {
        return ResponseEntity.ok(applicationService.getAppStatusCounts());
    }

    @Operation(summary = "Show counts of supported operations",
            description = "")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Showed counts of supported operations successfully"),
            @ApiResponse(responseCode = "404", description = "Show counts of supported operations failed")
    })
    @GetMapping("/supported-operations/counts")
    public ResponseEntity<List<CategoryCountDto>> getSupportedOperationsCounts() {
        return ResponseEntity.ok(applicationService.getSupportedOperationsCounts());
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

    @Operation(summary = "Show all available applications",
            description = "")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Showed all available applications successfully"),
            @ApiResponse(responseCode = "404", description = "Show all available applications failed")
    })
    @GetMapping("/applications")
    public ResponseEntity<List<ApplicationDto>> getAllApplications() {
        return ResponseEntity.ok(applicationService.getAllApplications());
    }
}
