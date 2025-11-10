/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.controller;

import com.evolveum.midpoint.integration.catalog.dto.UploadImplementationDto;
import com.evolveum.midpoint.integration.catalog.dto.ApplicationCardDto;
import com.evolveum.midpoint.integration.catalog.dto.ApplicationDto;
import com.evolveum.midpoint.integration.catalog.dto.CategoryCountDto;
import com.evolveum.midpoint.integration.catalog.dto.RequestFormDto;
import com.evolveum.midpoint.integration.catalog.form.ContinueForm;
import com.evolveum.midpoint.integration.catalog.form.FailForm;
import com.evolveum.midpoint.integration.catalog.form.SearchForm;
import com.evolveum.midpoint.integration.catalog.object.*;

import com.evolveum.midpoint.integration.catalog.repository.DownloadRepository;
import com.evolveum.midpoint.integration.catalog.repository.ImplementationVersionRepository;
import com.evolveum.midpoint.integration.catalog.repository.RequestRepository;
import com.evolveum.midpoint.integration.catalog.service.ApplicationService;
import com.evolveum.midpoint.integration.catalog.mapper.ApplicationMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@Tag(name = "Integration catalog", description = "API for managing endpoints of Integration catalog")
public class Controller {

    private final ApplicationService applicationService;

    private final ImplementationVersionRepository implementationVersionRepository;
    private final RequestRepository requestRepository;

    private final ApplicationMapper applicationMapper;

    public Controller(ApplicationService applicationService, DownloadRepository downloadRepository, ImplementationVersionRepository implementationVersionRepository, RequestRepository requestRepository, ApplicationMapper applicationMapper) {
        this.applicationService = applicationService;
        this.implementationVersionRepository = implementationVersionRepository;
        this.requestRepository = requestRepository;
        this.applicationMapper = applicationMapper;
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
            ApplicationDto dto = applicationMapper.mapToApplicationDto(app);
            return ResponseEntity.ok(dto);
        } catch (RuntimeException ex) {
            ex.printStackTrace();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to load application: " + ex.getMessage(), ex);
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
            return ResponseEntity.status(HttpStatus.OK).body(applicationService.uploadConnector(dto));
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

        String filename = version.getDownloadLink().substring(version.getDownloadLink().lastIndexOf('/') + 1);
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
    @GetMapping("/request/{id}")
    public ResponseEntity<Request> getRequest(@PathVariable Long id) {
        return applicationService.getRequest(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
    }

    @Operation(summary = "Get Request for Application ID",
            description = "")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Request for Application ID found"),
            @ApiResponse(responseCode = "404", description = "Request for Application ID not found")
    })
    @GetMapping("/applications/{appId}/request")
    public ResponseEntity<Request> getRequestForApplication(@PathVariable UUID appId) {
        return applicationService.getRequestForApplication(appId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found for application"));
    }

    @Operation(summary = "Create Request from Form",
            description = "Creates a new Application with lifecycle state REQUESTED and associated Request from the request form submission")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Request created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request data")
    })
    @PostMapping("/request")
    public ResponseEntity<Request> createRequest(@Valid @RequestBody RequestFormDto dto) {
        try {
            Request created = applicationService.createRequestFromForm(dto);
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
    @PostMapping("/request/{requestId}/vote")
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
    @GetMapping("/request/{requestId}/votes/count")
    public ResponseEntity<Long> getVoteCount(@PathVariable Long requestId) {
        long count = applicationService.getVoteCount(requestId);
        return ResponseEntity.ok(count);
    }

    @Operation(summary = "Check if user has voted",
            description = "Checks if a specific user has already voted for a request")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Check completed successfully")
    })
    @GetMapping("/request/{requestId}/votes/check")
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

    @Operation(summary = "Show all available applications",
            description = "Returns a list of application cards for display")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Showed all available applications successfully"),
            @ApiResponse(responseCode = "404", description = "Show all available applications failed")
    })
    @GetMapping("/applications")
    public ResponseEntity<List<ApplicationCardDto>> getAllApplications() {
        // Use list method without pagination to get all applications as cards
        Page<ApplicationCardDto> page = applicationService.list(Pageable.unpaged(), null, null);
        return ResponseEntity.ok(page.getContent());
    }
}
