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
import com.evolveum.midpoint.integration.catalog.service.LogoStorageService;
import com.evolveum.midpoint.integration.catalog.service.TutorialStorageService;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@Tag(name = "Integration catalog", description = "API for managing endpoints of Integration catalog")
public class Controller {

    private final ApplicationService applicationService;
    private final LogoStorageService logoStorageService;
    private final TutorialStorageService tutorialStorageService;
    private final RequestRepository requestRepository;
    private final ApplicationMapper applicationMapper;

    public Controller(ApplicationService applicationService,
                      LogoStorageService logoStorageService,
                      TutorialStorageService tutorialStorageService,
                      RequestRepository requestRepository,
                      ApplicationMapper applicationMapper) {
        this.applicationService = applicationService;
        this.logoStorageService = logoStorageService;
        this.tutorialStorageService = tutorialStorageService;
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
        try {
            return ResponseEntity.ok(applicationService.getApplicationTags());
        } catch (RuntimeException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Get all integration method types",
            description = "Fetches all available integration method types")
    @ApiResponse(responseCode = "200", description = "Integration method types retrieved successfully")
    @GetMapping("/integration-method-types")
    public ResponseEntity<List<IntegrationMethodType>> getIntegrationMethodTypes() {
        return ResponseEntity.ok(applicationService.getIntegrationMethodTypes());
    }

    @Operation(summary = "Get all MidPoint versions",
            description = "Fetches all available MidPoint versions")
    @ApiResponse(responseCode = "200", description = "MidPoint versions retrieved successfully")
    @GetMapping("/midpoint-versions")
    public ResponseEntity<List<MidpointVersionDto>> getMidpointVersions() {
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
        try {
            return ResponseEntity.ok(applicationService.getCountriesOfOrigin());
        } catch (RuntimeException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Check if connector bundle name exists", description = "Returns true if the specified bundle name is already taken")
    @GetMapping("/upload/check-bundle-name")
    public ResponseEntity<Boolean> checkBundleNameExists(@RequestParam String bundleName) {
        boolean exists = applicationService.checkBundleNameExists(bundleName);
        return ResponseEntity.ok(exists);
    }

    @Operation(summary = "Upload connector implementation")
    @PostMapping("/upload/connector")
    public ResponseEntity<String> uploadConnector(
            @RequestBody UploadImplementationDto dto,
            @RequestHeader(value = "X-User-Name", required = false, defaultValue = "anonymous") String username) {
        try {
            return ResponseEntity.status(HttpStatus.OK).body(applicationService.uploadConnector(dto, username));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
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
        applicationService.failBuild(oid, failForm);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Download connector by integration method ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Download successful"),
            @ApiResponse(responseCode = "404", description = "Not found")
    })
    @GetMapping("/downloads/{oid}")
    public ResponseEntity<byte[]> downloadConnector(@PathVariable UUID oid, HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        String ua = request.getHeader("User-Agent");
        try {
            byte[] fileBytes = applicationService.downloadConnector(oid, ip, ua);
            if (fileBytes == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No download data returned");
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDisposition(org.springframework.http.ContentDisposition.builder("attachment")
                    .filename("connector-" + oid + ".jar")
                    .build());
            headers.setContentLength(fileBytes.length);
            return new ResponseEntity<>(fileBytes, headers, HttpStatus.OK);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to download connector: " + e.getMessage(), e);
        }
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
        try {
            return ResponseEntity.ok(applicationService.searchApplication(searchForm, page, size));
        } catch (RuntimeException ex) {
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
        try {
            return ResponseEntity.ok(applicationService.searchIntegrationMethods(searchForm, page, size));
        } catch (RuntimeException ex) {
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
        try {
            Request created = applicationService.createRequestFromForm(dto);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
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
        try {
            applicationService.cancelRequest(requestId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (Exception ex) {
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

    @Operation(summary = "Show counts of categories")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Category counts retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Failed to retrieve category counts")
    })
    @GetMapping("/categories/counts")
    public ResponseEntity<List<CategoryCountDto>> getCategoryCounts() {
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
        return ResponseEntity.ok(applicationService.listActiveConnectors());
    }

    @Operation(summary = "Get catalog connectors",
            description = "Returns connector bundles in ACTIVE lifecycle state for use in the publish form connector picker")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Catalog connectors retrieved successfully")
    })
    @GetMapping("/connectors/catalog")
    public ResponseEntity<List<CatalogConnectorDto>> getCatalogConnectors() {
        return ResponseEntity.ok(applicationService.listCatalogConnectors());
    }

    @Operation(summary = "Get available capabilities",
            description = "Returns a list of all available capability types")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Capabilities retrieved successfully")
    })
    @GetMapping("/capabilities")
    public ResponseEntity<List<CapabilityDto>> getCapabilities() {
        return ResponseEntity.ok(applicationService.getCapabilities());
    }

    @Operation(summary = "Get total downloads count",
            description = "Returns the total number of downloads across all applications")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Total downloads count retrieved successfully")
    })
    @GetMapping("/statistics/downloads-count")
    public ResponseEntity<Long> getTotalDownloadsCount() {
        return ResponseEntity.ok(applicationService.getTotalDownloadsCount());
    }

    @Operation(summary = "Get downloads count for an application",
            description = "Returns the total number of downloads for a specific application")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Application downloads count retrieved successfully")
    })
    @GetMapping("/applications/{applicationId}/downloads-count")
    public ResponseEntity<Long> getApplicationDownloadsCount(@PathVariable UUID applicationId) {
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
        try {
            return ResponseEntity.status(HttpStatus.OK).body(applicationService.verify(verifyPayload));
        } catch (IllegalArgumentException e) {
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
        try {
            String newRevision = applicationService.editIntegrationMethod(methodId, currentRevision, dto);
            return ResponseEntity.ok(newRevision);
        } catch (RuntimeException e) {
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
        try {
            Application application = applicationService.getApplication(id);
            logoStorageService.saveLogo(application, file);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (RuntimeException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("not found")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to upload logo: " + ex.getMessage(), ex);
        } catch (IOException ex) {
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
        try {
            tutorialStorageService.saveTutorial(id, file);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (RuntimeException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("not found")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to upload tutorial: " + ex.getMessage(), ex);
        } catch (IOException ex) {
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
        try {
            tutorialStorageService.saveTutorialForRevision(methodId, revision, file);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (RuntimeException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("not found")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to upload tutorial: " + ex.getMessage(), ex);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to save tutorial file: " + ex.getMessage(), ex);
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
        Application application;
        try {
            application = applicationService.getApplication(id);
        } catch (RuntimeException ex) {
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
        try {
            Application application = applicationService.getApplication(id);
            logoStorageService.deleteLogo(application);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("not found")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
            }
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
        applicationService.recordRecentlyUsed(applicationId, username);
        return ResponseEntity.noContent().build();
    }
}
