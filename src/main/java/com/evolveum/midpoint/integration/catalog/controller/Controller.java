/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.controller;

import com.evolveum.midpoint.integration.catalog.dto.*;
import com.evolveum.midpoint.integration.catalog.form.ContinueForm;
import com.evolveum.midpoint.integration.catalog.form.FailForm;
import com.evolveum.midpoint.integration.catalog.form.SearchForm;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.evolveum.midpoint.integration.catalog.repository.RequestRepository;
import com.evolveum.midpoint.integration.catalog.service.ApplicationService;
import com.evolveum.midpoint.integration.catalog.service.BundleService;
import com.evolveum.midpoint.integration.catalog.service.LogoStorageService;
import com.evolveum.midpoint.integration.catalog.service.TutorialStorageService;
import com.evolveum.midpoint.integration.catalog.mapper.ApplicationMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api")
@Tag(name = "Integration catalog", description = "API for managing endpoints of Integration catalog")
public class Controller {

    private final ApplicationService applicationService;
    private final LogoStorageService logoStorageService;
    private final TutorialStorageService tutorialStorageService;
    private final BundleService bundleService;
    private final RequestRepository requestRepository;
    private final ApplicationMapper applicationMapper;

    public Controller(ApplicationService applicationService,
                      LogoStorageService logoStorageService,
                      TutorialStorageService tutorialStorageService,
                      BundleService bundleService,
                      RequestRepository requestRepository,
                      ApplicationMapper applicationMapper) {
        this.applicationService = applicationService;
        this.logoStorageService = logoStorageService;
        this.tutorialStorageService = tutorialStorageService;
        this.bundleService = bundleService;
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
        log.debug("getApplication id={}", id);
        try {
            Application app = applicationService.getApplication(id);
            ApplicationDto dto = applicationMapper.mapToApplicationDto(app);
            return ResponseEntity.ok(dto);
        } catch (RuntimeException ex) {
            log.error("getApplication failed id={}: {}", id, ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to load application: " + ex.getMessage(), ex);
        }
    }

    @Operation(summary = "Get all application tags",
            description = "Fetches all application tags")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Application tags found"),
            @ApiResponse(responseCode = "404", description = "Application tags not found")
    })
    @GetMapping("/application-tags")
    public ResponseEntity<List<ApplicationTagDto>> getApplicationTags() {
        log.debug("getApplicationTags");
        try {
            return ResponseEntity.ok(applicationService.getApplicationTags());
        } catch (RuntimeException ex) {
            log.warn("getApplicationTags failed: {}", ex.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Get all integration method types",
            description = "Fetches all available integration method types")
    @ApiResponse(responseCode = "200", description = "Integration method types retrieved successfully")
    @GetMapping("/integration-method-types")
    public ResponseEntity<List<IntegrationMethodType>> getIntegrationMethodTypes() {
        log.debug("getIntegrationMethodTypes");
        return ResponseEntity.ok(applicationService.getIntegrationMethodTypes());
    }

    @Operation(summary = "Get all MidPoint versions",
            description = "Fetches all available MidPoint versions")
    @ApiResponse(responseCode = "200", description = "MidPoint versions retrieved successfully")
    @GetMapping("/midpoint-versions")
    public ResponseEntity<List<MidpointVersionDto>> getMidpointVersions() {
        log.debug("getMidpointVersions");
        return ResponseEntity.ok(applicationService.getMidpointVersions());
    }

    @Operation(summary = "Get all countries of origin",
            description = "Fetches all countries of origin")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Countries of origin found"),
            @ApiResponse(responseCode = "404", description = "Countries of origin not found")
    })
    @GetMapping("/countries-of-origin")
    public ResponseEntity<List<CountryOfOrigin>> getCountriesOfOrigin() {
        log.debug("getCountriesOfOrigin");
        try {
            return ResponseEntity.ok(applicationService.getCountriesOfOrigin());
        } catch (RuntimeException ex) {
            log.warn("getCountriesOfOrigin failed: {}", ex.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Check if connector bundle name exists", description = "Returns true if the specified bundle name is already taken")
    @GetMapping("/upload/check-bundle-name")
    public ResponseEntity<Boolean> checkBundleNameExists(@RequestParam String bundleName) {
        log.debug("checkBundleNameExists bundleName={}", bundleName);
        boolean exists = applicationService.checkBundleNameExists(bundleName);
        return ResponseEntity.ok(exists);
    }

    @Operation(summary = "Upload connector implementation")
    @PostMapping("/upload/connector")
    public ResponseEntity<String> uploadConnector(
            @RequestBody UploadImplementationDto dto,
            @RequestHeader(value = "X-User-Name", required = false, defaultValue = "anonymous") String username) {
        log.info("uploadConnector user={}", username);
        try {
            return ResponseEntity.status(HttpStatus.OK).body(applicationService.uploadConnector(dto, username));
        } catch (IllegalArgumentException e) {
            log.warn("uploadConnector rejected user={}: {}", username, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.warn("uploadConnector data conflict user={}: {}", username, e.getMostSpecificCause().getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Publish failed due to a data conflict (e.g. duplicate bundle version). " +
                "Please ensure the database migration 03_fix_bundle_version_unique_constraint.sql has been applied. " +
                "Details: " + e.getMostSpecificCause().getMessage());
        }
    }

    @Operation(summary = "Upload status - success")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Upload status - success worked"),
            @ApiResponse(responseCode = "404", description = "Upload status - success did not work")
    })
    @PostMapping("/upload/continue/{oid}")
    public ResponseEntity<Void> completeBuildSuccessfully(@RequestBody ContinueForm continueForm, @PathVariable UUID oid) {
        log.info("completeBuildSuccessfully oid={}", oid);
        applicationService.successBuild(oid, continueForm);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Upload status - fail")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Upload status - failed"),
            @ApiResponse(responseCode = "404", description = "Upload status - did not fail")
    })
    @PostMapping("/upload/continue/fail/{oid}")
    public ResponseEntity<Void> completeBuildWithFailure(@RequestBody FailForm failForm, @PathVariable UUID oid) {
        log.info("completeBuildWithFailure oid={}", oid);
        applicationService.failBuild(oid, failForm);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Search applications by parameters")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Applications found"),
            @ApiResponse(responseCode = "404", description = "Applications not found")
    })
    @PostMapping("/applications/search/{size}/{page}")
    public ResponseEntity<Page<Application>> searchApplication(
            @RequestBody SearchForm searchForm,
            @PathVariable int size,
            @PathVariable int page) {
        log.debug("searchApplication page={} size={}", page, size);
        try {
            return ResponseEntity.ok(applicationService.searchApplication(searchForm, page, size));
        } catch (RuntimeException ex) {
            log.warn("searchApplication failed page={} size={}: {}", page, size, ex.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Search integration methods by parameters")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Integration methods found"),
            @ApiResponse(responseCode = "404", description = "Integration methods not found")
    })
    @PostMapping("/integration-methods/search/{size}/{page}")
    public ResponseEntity<Page<IntegrationMethod>> searchIntegrationMethods(
            @RequestBody SearchForm searchForm,
            @PathVariable int size,
            @PathVariable int page) {
        log.debug("searchIntegrationMethods page={} size={}", page, size);
        try {
            return ResponseEntity.ok(applicationService.searchIntegrationMethods(searchForm, page, size));
        } catch (RuntimeException ex) {
            log.warn("searchIntegrationMethods failed page={} size={}: {}", page, size, ex.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Get Request by ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Request found"),
            @ApiResponse(responseCode = "404", description = "Request not found")
    })
    @GetMapping("/requests/{id}")
    public ResponseEntity<Request> getRequest(@PathVariable Long id) {
        log.debug("getRequest id={}", id);
        return applicationService.getRequest(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
    }

    @Operation(summary = "Get Request for Application ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Request found"),
            @ApiResponse(responseCode = "404", description = "Request not found")
    })
    @GetMapping("/applications/{appId}/request")
    public ResponseEntity<Request> getRequestForApplication(@PathVariable UUID appId) {
        log.debug("getRequestForApplication appId={}", appId);
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
    @PostMapping("/requests")
    public ResponseEntity<Request> createRequest(@Valid @RequestBody RequestFormDto dto) {
        log.info("createRequest");
        try {
            Request created = applicationService.createRequestFromForm(dto);
            log.info("createRequest created id={}", created.getId());
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException ex) {
            log.warn("createRequest rejected: {}", ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            log.error("createRequest failed: {}", ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create request: " + ex.getMessage());
        }
    }

    @Operation(summary = "Cancel request",
            description = "Cancels a request and deletes its associated application")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Request cancelled successfully"),
            @ApiResponse(responseCode = "404", description = "Request not found")
    })
    @DeleteMapping("/requests/{requestId}")
    public ResponseEntity<Void> cancelRequest(@PathVariable Long requestId) {
        log.info("cancelRequest requestId={}", requestId);
        try {
            applicationService.cancelRequest(requestId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException ex) {
            log.warn("cancelRequest not found requestId={}: {}", requestId, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (Exception ex) {
            log.error("cancelRequest failed requestId={}: {}", requestId, ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to cancel request: " + ex.getMessage());
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
        log.info("submitVote requestId={} voter={}", requestId, voter);
        try {
            Vote vote = applicationService.submitVote(requestId, voter);
            return ResponseEntity.status(HttpStatus.CREATED).body(vote);
        } catch (IllegalArgumentException ex) {
            log.warn("submitVote rejected requestId={} voter={}: {}", requestId, voter, ex.getMessage());
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
        log.debug("getVoteCount requestId={}", requestId);
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
        log.debug("hasUserVoted requestId={} voter={}", requestId, voter);
        boolean hasVoted = applicationService.hasUserVoted(requestId, voter);
        return ResponseEntity.ok(hasVoted);
    }

    @Operation(summary = "Show counts of categories")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Category counts retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Failed to retrieve category counts")
    })
    @GetMapping("/categories/counts")
    public ResponseEntity<List<CategoryCountDto>> getCategoryCounts() {
        log.debug("getCategoryCounts");
        return ResponseEntity.ok(applicationService.getCategoryCounts());
    }

    @Operation(summary = "Show all available applications",
            description = "Returns a list of application cards for display")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Applications retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Failed to retrieve applications")
    })
    @GetMapping("/applications")
    public ResponseEntity<List<ApplicationCardDto>> getAllApplications() {
        log.debug("getAllApplications");
        Page<ApplicationCardDto> page = applicationService.list(Pageable.unpaged(), null, null);
        return ResponseEntity.ok(page.getContent());
    }

    @Operation(summary = "Get all active connectors",
            description = "Returns a list of all connectors with ACTIVE lifecycle state")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Active connectors retrieved successfully")
    })
    @GetMapping("/connectors/active")
    public ResponseEntity<List<ActiveConnectorDto>> getActiveConnectors() {
        log.debug("getActiveConnectors");
        return ResponseEntity.ok(applicationService.listActiveConnectors());
    }

    @Operation(summary = "Get catalog connectors",
            description = "Returns connector bundles in ACTIVE lifecycle state for use in the publish form connector picker")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Catalog connectors retrieved successfully")
    })
    @GetMapping("/connectors/catalog")
    public ResponseEntity<List<CatalogConnectorDto>> getCatalogConnectors() {
        log.debug("getCatalogConnectors");
        return ResponseEntity.ok(applicationService.listCatalogConnectors());
    }

    @Operation(summary = "Get available capabilities",
            description = "Returns a list of all available capability types")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Capabilities retrieved successfully")
    })
    @GetMapping("/capabilities")
    public ResponseEntity<List<CapabilityDto>> getCapabilities() {
        log.debug("getCapabilities");
        return ResponseEntity.ok(applicationService.getCapabilities());
    }

    @Operation(summary = "Get total downloads count",
            description = "Returns the total number of downloads across all applications")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Total downloads count retrieved successfully")
    })
    @GetMapping("/statistics/downloads-count")
    public ResponseEntity<Long> getTotalDownloadsCount() {
        log.debug("getTotalDownloadsCount");
        return ResponseEntity.ok(applicationService.getTotalDownloadsCount());
    }

    @Operation(summary = "Get downloads count for an application",
            description = "Returns the total number of downloads for a specific application")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Application downloads count retrieved successfully")
    })
    @GetMapping("/applications/{applicationId}/downloads-count")
    public ResponseEntity<Long> getApplicationDownloadsCount(@PathVariable UUID applicationId) {
        log.debug("getApplicationDownloadsCount applicationId={}", applicationId);
        return ResponseEntity.ok(applicationService.countDownloadsForApplication(applicationId));
    }

    @Operation(summary = "Verify bundle information after build")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Bundle verified successfully"),
            @ApiResponse(responseCode = "400", description = "Verification failed"),
            @ApiResponse(responseCode = "404", description = "Bundle not found"),
            @ApiResponse(responseCode = "409", description = "Connector class already exists for this bundle version")
    })
    @PostMapping("/upload/verify")
    public ResponseEntity<Boolean> verify(@RequestBody VerifyBundleInformationForm verifyPayload) {
        log.info("verify bundle");
        try {
            return ResponseEntity.status(HttpStatus.OK).body(applicationService.verify(verifyPayload));
        } catch (IllegalArgumentException e) {
            log.warn("verify rejected: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @Operation(summary = "Get integration methods for application",
            description = "Returns all integration methods for a specific application")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Integration methods retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Application not found")
    })
    @GetMapping("/applications/{applicationId}/implementations")
    public ResponseEntity<List<ImplementationListItemDto>> getIntegrationMethodsByApplicationId(@PathVariable UUID applicationId) {
        log.debug("getIntegrationMethodsByApplicationId applicationId={}", applicationId);
        List<ImplementationListItemDto> items = applicationService.getIntegrationMethodsByApplicationId(applicationId);
        return ResponseEntity.ok(items);
    }

    @Operation(summary = "Save integration method as new version")
    @PutMapping("/applications/{appId}/integration-method/{methodId}/{currentRevision}")
    public ResponseEntity<String> editIntegrationMethod(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String currentRevision,
            @RequestBody EditIntegrationMethodDto dto) {
        log.info("editIntegrationMethod appId={} methodId={} currentRevision={}", appId, methodId, currentRevision);
        try {
            String newRevision = applicationService.editIntegrationMethod(methodId, currentRevision, dto);
            log.info("editIntegrationMethod saved methodId={} newRevision={}", methodId, newRevision);
            return ResponseEntity.ok(newRevision);
        } catch (IllegalStateException e) {
            log.warn("editIntegrationMethod conflict methodId={}: {}", methodId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("editIntegrationMethod not found methodId={}: {}", methodId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @Operation(summary = "Publish (approve) an in-review integration method revision",
            description = "Activates an in-review revision. A minor revision replaces its same-major "
                    + "published baseline; a new major version is kept alongside earlier majors.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Revision published"),
            @ApiResponse(responseCode = "404", description = "Integration method revision not found"),
            @ApiResponse(responseCode = "409", description = "Revision is not in review")
    })
    @PostMapping("/applications/{appId}/integration-method/{methodId}/{revision}/publish")
    public ResponseEntity<Void> publishIntegrationMethod(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision,
            @RequestHeader(value = "X-User-Name", required = false, defaultValue = "anonymous") String username) {
        log.info("publishIntegrationMethod appId={} methodId={} revision={} user={}", appId, methodId, revision, username);
        try {
            applicationService.publishIntegrationMethod(methodId, revision, username);
            return ResponseEntity.ok().build();
        } catch (IllegalStateException e) {
            log.warn("publishIntegrationMethod conflict methodId={} revision={}: {}", methodId, revision, e.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("publishIntegrationMethod not found methodId={} revision={}: {}", methodId, revision, e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @Operation(summary = "Reject an in-review integration method revision",
            description = "Marks an in-review revision as REJECTED and records the reviewing user. "
                    + "The revision is kept for auditability.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Revision rejected"),
            @ApiResponse(responseCode = "404", description = "Integration method revision not found"),
            @ApiResponse(responseCode = "409", description = "Revision is not in review")
    })
    @PostMapping("/applications/{appId}/integration-method/{methodId}/{revision}/reject")
    public ResponseEntity<Void> rejectIntegrationMethod(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision,
            @RequestHeader(value = "X-User-Name", required = false, defaultValue = "anonymous") String username) {
        log.info("rejectIntegrationMethod appId={} methodId={} revision={} user={}", appId, methodId, revision, username);
        try {
            applicationService.rejectIntegrationMethod(methodId, revision, username);
            return ResponseEntity.ok().build();
        } catch (IllegalStateException e) {
            log.warn("rejectIntegrationMethod conflict methodId={} revision={}: {}", methodId, revision, e.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("rejectIntegrationMethod not found methodId={} revision={}: {}", methodId, revision, e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @Operation(summary = "Add a connector to an integration method revision",
            description = "Links a connector (existing or newly created) to the given integration method revision.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Connector added successfully"),
            @ApiResponse(responseCode = "404", description = "Integration method or connector not found")
    })
    @PostMapping("/applications/{appId}/integration-method/{methodId}/{revision}/connectors")
    public ResponseEntity<Void> addConnectorToIntegrationMethod(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision,
            @RequestBody AddConnectorDto dto,
            @RequestHeader(value = "X-User-Name", required = false, defaultValue = "anonymous") String username) {
        log.info("addConnectorToIntegrationMethod appId={} methodId={} revision={} user={}", appId, methodId, revision, username);
        try {
            applicationService.addConnectorToIntegrationMethod(appId, methodId, revision, dto, username);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            log.warn("addConnectorToIntegrationMethod not found methodId={} revision={}: {}", methodId, revision, e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @Operation(summary = "List connectors of an integration method revision")
    @GetMapping("/applications/{appId}/integration-method/{methodId}/{revision}/connectors")
    public ResponseEntity<List<ImplementationListItemDto>> getConnectorsForIntegrationMethod(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision) {
        log.debug("getConnectorsForIntegrationMethod methodId={} revision={}", methodId, revision);
        return ResponseEntity.ok(applicationService.getConnectorsForIntegrationMethod(methodId, revision));
    }

    @Operation(summary = "Update a connector of an integration method revision",
            description = "Replaces the fields of an existing connector (and its bundle / latest version) in place.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Connector updated successfully"),
            @ApiResponse(responseCode = "404", description = "Integration method or connector not found")
    })
    @PutMapping("/applications/{appId}/integration-method/{methodId}/{revision}/connectors/{connectorId}")
    public ResponseEntity<Void> updateConnector(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision,
            @PathVariable Integer connectorId,
            @RequestBody EditConnectorDto dto) {
        log.info("updateConnector appId={} methodId={} revision={} connectorId={}", appId, methodId, revision, connectorId);
        try {
            applicationService.updateConnector(methodId, revision, connectorId, dto);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            log.warn("updateConnector not found methodId={} connectorId={}: {}", methodId, connectorId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @Operation(summary = "Set the connector compatibility range for an integration method revision",
            description = "Updates the connector version range (min/max) that this integration method supports.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Compatibility updated successfully"),
            @ApiResponse(responseCode = "404", description = "Integration method or connector not found")
    })
    @PutMapping("/applications/{appId}/integration-method/{methodId}/{revision}/connectors/{connectorId}/compatibility")
    public ResponseEntity<Void> updateConnectorCompatibility(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision,
            @PathVariable Integer connectorId,
            @RequestBody UpdateConnectorCompatibilityDto dto) {
        log.info("updateConnectorCompatibility appId={} methodId={} revision={} connectorId={}", appId, methodId, revision, connectorId);
        try {
            applicationService.updateConnectorCompatibility(methodId, revision, connectorId,
                    dto.connectorVersionFrom(), dto.connectorVersionTo());
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            log.warn("updateConnectorCompatibility not found methodId={} connectorId={}: {}", methodId, connectorId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @Operation(summary = "Remove a connector from an integration method revision",
            description = "Unlinks a connector from the given integration method revision. The connector itself is left intact.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Connector removed successfully"),
            @ApiResponse(responseCode = "404", description = "Integration method or connector not found")
    })
    @DeleteMapping("/applications/{appId}/integration-method/{methodId}/{revision}/connectors/{connectorId}")
    public ResponseEntity<Void> deleteConnectorFromIntegrationMethod(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision,
            @PathVariable Integer connectorId) {
        log.info("deleteConnectorFromIntegrationMethod appId={} methodId={} revision={} connectorId={}", appId, methodId, revision, connectorId);
        try {
            applicationService.deleteConnectorFromIntegrationMethod(methodId, revision, connectorId);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            log.warn("deleteConnectorFromIntegrationMethod not found methodId={} connectorId={}: {}", methodId, connectorId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // ==================== Logo Endpoints ====================

    @Operation(summary = "Upload application logo",
            description = "Uploads a logo image for an application. Supported formats: PNG, JPEG, GIF, SVG, WebP")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Logo uploaded successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid file (wrong type, too large, or empty)"),
            @ApiResponse(responseCode = "404", description = "Application not found"),
            @ApiResponse(responseCode = "500", description = "Failed to save logo file")
    })
    @PostMapping(value = "/applications/{id}/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> uploadLogo(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file) {
        log.info("uploadLogo id={} filename={} size={}", id, file.getOriginalFilename(), file.getSize());
        try {
            Application application = applicationService.getApplication(id);
            logoStorageService.saveLogo(application, file);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException ex) {
            log.warn("uploadLogo rejected id={}: {}", id, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (RuntimeException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("not found")) {
                log.warn("uploadLogo not found id={}: {}", id, ex.getMessage());
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
            }
            log.error("uploadLogo failed id={}: {}", id, ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to upload logo: " + ex.getMessage(), ex);
        } catch (IOException ex) {
            log.error("uploadLogo file save failed id={}: {}", id, ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to save logo file: " + ex.getMessage(), ex);
        }
    }

    @Operation(summary = "Upload tutorial file for an integration method",
            description = "Uploads a tutorial document (PDF, XML, JSON, YAML, TXT) for an integration method. Stored on disk; path saved to integration_method.file_path.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Tutorial uploaded successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid file (wrong type, too large, or empty)"),
            @ApiResponse(responseCode = "404", description = "Integration method not found"),
            @ApiResponse(responseCode = "500", description = "Failed to save tutorial file")
    })
    @PostMapping(value = "/integration-methods/{id}/tutorial", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> uploadTutorial(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file) {
        log.info("uploadTutorial methodId={} filename={} size={}", id, file.getOriginalFilename(), file.getSize());
        try {
            tutorialStorageService.saveTutorial(id, file);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException ex) {
            log.warn("uploadTutorial rejected methodId={}: {}", id, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (RuntimeException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("not found")) {
                log.warn("uploadTutorial not found methodId={}: {}", id, ex.getMessage());
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
            }
            log.error("uploadTutorial failed methodId={}: {}", id, ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to upload tutorial: " + ex.getMessage(), ex);
        } catch (IOException ex) {
            log.error("uploadTutorial file save failed methodId={}: {}", id, ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to save tutorial file: " + ex.getMessage(), ex);
        }
    }

    @Operation(summary = "Upload tutorial file for a specific integration method revision")
    @PostMapping(value = "/applications/{appId}/integration-method/{methodId}/{revision}/tutorial", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> uploadTutorialForRevision(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision,
            @RequestParam("file") MultipartFile file) {
        log.info("uploadTutorialForRevision appId={} methodId={} revision={} filename={} size={}",
                appId, methodId, revision, file.getOriginalFilename(), file.getSize());
        try {
            tutorialStorageService.saveTutorialForRevision(methodId, revision, file);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException ex) {
            log.warn("uploadTutorialForRevision rejected methodId={} revision={}: {}", methodId, revision, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (RuntimeException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("not found")) {
                log.warn("uploadTutorialForRevision not found methodId={} revision={}: {}", methodId, revision, ex.getMessage());
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
            }
            log.error("uploadTutorialForRevision failed methodId={} revision={}: {}", methodId, revision, ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to upload tutorial: " + ex.getMessage(), ex);
        } catch (IOException ex) {
            log.error("uploadTutorialForRevision file save failed methodId={} revision={}: {}", methodId, revision, ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to save tutorial file: " + ex.getMessage(), ex);
        }
    }

    @Operation(summary = "List tutorial files for a specific integration method revision")
    @GetMapping("/applications/{appId}/integration-method/{methodId}/{revision}/tutorial")
    public ResponseEntity<List<String>> listTutorialFiles(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision) {
        log.debug("listTutorialFiles methodId={} revision={}", methodId, revision);
        return ResponseEntity.ok(tutorialStorageService.listTutorialFiles(methodId, revision));
    }

    @Operation(summary = "Download a single tutorial file for a specific integration method revision")
    @GetMapping("/applications/{appId}/integration-method/{methodId}/{revision}/tutorial/file")
    public ResponseEntity<byte[]> downloadTutorialFile(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision,
            @RequestParam("name") String name) {
        log.debug("downloadTutorialFile methodId={} revision={} name={}", methodId, revision, name);
        try {
            Path file = tutorialStorageService.resolveTutorialFile(methodId, revision, name);
            byte[] bytes = Files.readAllBytes(file);
            String contentType = Files.probeContentType(file);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, contentType != null ? contentType : MediaType.APPLICATION_OCTET_STREAM_VALUE)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                    .body(bytes);
        } catch (IllegalArgumentException ex) {
            log.warn("downloadTutorialFile rejected methodId={} name={}: {}", methodId, name, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("downloadTutorialFile not found methodId={} name={}: {}", methodId, name, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (IOException ex) {
            log.error("downloadTutorialFile read failed methodId={} name={}: {}", methodId, name, ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to read tutorial file: " + ex.getMessage(), ex);
        }
    }

    @Operation(summary = "Download a ZIP bundle for an integration method revision",
            description = "Bundles the tutorial (tutorial.adoc, converted from Markdown) and all uploaded tutorial files into a single ZIP.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Bundle built successfully"),
            @ApiResponse(responseCode = "404", description = "Integration method not found"),
            @ApiResponse(responseCode = "500", description = "Failed to build bundle")
    })
    @GetMapping("/applications/{appId}/integration-method/{methodId}/{revision}/bundle")
    public ResponseEntity<byte[]> downloadBundle(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision,
            HttpServletRequest request) {
        log.info("downloadBundle appId={} methodId={} revision={}", appId, methodId, revision);
        try {
            BundleService.Bundle bundle = bundleService.buildBundle(methodId, revision);
            try {
                applicationService.recordMethodDownload(methodId, revision,
                        request.getRemoteAddr(), request.getHeader("User-Agent"));
            } catch (RuntimeException ex) {
                log.warn("Failed to record download for {}/{}: {}", methodId, revision, ex.getMessage());
            }
            ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/zip")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + bundle.fileName() + "\"");
            if (bundle.warning() != null) {
                responseBuilder.header("X-Bundle-Warning", bundle.warning());
            }
            return responseBuilder.body(bundle.data());
        } catch (IllegalArgumentException ex) {
            log.warn("downloadBundle not found methodId={} revision={}: {}", methodId, revision, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (IOException ex) {
            log.error("downloadBundle build failed methodId={} revision={}: {}", methodId, revision, ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to build bundle: " + ex.getMessage(), ex);
        }
    }

    @Operation(summary = "Delete a single tutorial file for a specific integration method revision")
    @DeleteMapping("/applications/{appId}/integration-method/{methodId}/{revision}/tutorial/file")
    public ResponseEntity<Void> deleteTutorialFile(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision,
            @RequestParam("name") String name) {
        log.info("deleteTutorialFile methodId={} revision={} name={}", methodId, revision, name);
        try {
            tutorialStorageService.deleteTutorialFile(methodId, revision, name);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException ex) {
            log.warn("deleteTutorialFile rejected methodId={} name={}: {}", methodId, name, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (IOException ex) {
            log.error("deleteTutorialFile failed methodId={} name={}: {}", methodId, name, ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to delete tutorial file: " + ex.getMessage(), ex);
        }
    }

    @Operation(summary = "Get application logo",
            description = "Returns the logo image for an application with proper Content-Type header")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Logo retrieved successfully"),
            @ApiResponse(responseCode = "304", description = "Logo not modified (ETag match)"),
            @ApiResponse(responseCode = "404", description = "Application or logo not found")
    })
    @GetMapping("/applications/{id}/logo")
    public ResponseEntity<byte[]> getLogo(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        log.debug("getLogo id={}", id);
        Application application;
        try {
            application = applicationService.getApplication(id);
        } catch (RuntimeException ex) {
            log.warn("getLogo application not found id={}: {}", id, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found");
        }

        if (application.getLogoPath() == null || application.getLogoPath().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No logo available for this application");
        }

        byte[] logoBytes = logoStorageService.loadLogoBytes(application.getLogoPath());
        if (logoBytes == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Logo file not found on disk");
        }

        String etag = "\"" + application.getLogoPath().hashCode() + "-" + logoBytes.length + "\"";
        if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }

        String contentType = detectContentType(application.getLogoPath());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.setContentLength(logoBytes.length);
        headers.setCacheControl("public, max-age=86400");
        headers.setETag(etag);

        return new ResponseEntity<>(logoBytes, headers, HttpStatus.OK);
    }

    private String detectContentType(String logoPath) {
        if (logoPath == null) return "image/png";
        String lower = logoPath.toLowerCase();
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    @Operation(summary = "Delete application logo",
            description = "Removes the logo file and clears the logo path from the application")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Logo deleted successfully"),
            @ApiResponse(responseCode = "404", description = "Application not found")
    })
    @DeleteMapping("/applications/{id}/logo")
    public ResponseEntity<Void> deleteLogo(@PathVariable UUID id) {
        log.info("deleteLogo id={}", id);
        try {
            Application application = applicationService.getApplication(id);
            logoStorageService.deleteLogo(application);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("not found")) {
                log.warn("deleteLogo not found id={}: {}", id, ex.getMessage());
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
            }
            log.error("deleteLogo failed id={}: {}", id, ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to delete logo: " + ex.getMessage(), ex);
        }
    }

    // ==================== Recently Used Applications ====================

    @Operation(summary = "Get recently used applications",
            description = "Returns a global list of recently used applications across all users")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Recently used applications retrieved successfully")
    })
    @GetMapping("/recently-used")
    public ResponseEntity<List<ApplicationDto>> getRecentlyUsed() {
        log.debug("getRecentlyUsed");
        return ResponseEntity.ok(applicationService.getRecentlyUsedApplications());
    }

    @Operation(summary = "Record recently used application",
            description = "Records that an application was used, updating the global recently used list")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Recorded successfully")
    })
    @PostMapping("/recently-used/{applicationId}")
    public ResponseEntity<Void> recordRecentlyUsed(
            @PathVariable UUID applicationId,
            @RequestHeader(value = "X-User-Name", required = false, defaultValue = "anonymous") String username) {
        log.info("recordRecentlyUsed applicationId={} user={}", applicationId, username);
        applicationService.recordRecentlyUsed(applicationId, username);
        return ResponseEntity.noContent().build();
    }
}
