/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.controller;

import com.evolveum.midpoint.integration.catalog.dto.*;
import com.evolveum.midpoint.integration.catalog.service.ApplicationService;
import com.evolveum.midpoint.integration.catalog.service.BundleService;
import com.evolveum.midpoint.integration.catalog.service.SupportTicketService;
import com.evolveum.midpoint.integration.catalog.service.TutorialStorageService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
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

/**
 * Everything addressed to one revision of one integration method, split off {@link Controller} so
 * that class is not the single place every catalog endpoint lands in.
 */
@Slf4j
@RestController
@RequestMapping("/api/applications/{appId}/integration-method/{methodId}/{revision}")
@Tag(name = "Integration method revision",
        description = "API for a single revision of an integration method")
public class IntegrationMethodController {

    private final ApplicationService applicationService;
    private final TutorialStorageService tutorialStorageService;
    private final BundleService bundleService;
    private final SupportTicketService supportTicketService;

    public IntegrationMethodController(ApplicationService applicationService,
                                       TutorialStorageService tutorialStorageService,
                                       BundleService bundleService,
                                       SupportTicketService supportTicketService) {
        this.applicationService = applicationService;
        this.tutorialStorageService = tutorialStorageService;
        this.bundleService = bundleService;
        this.supportTicketService = supportTicketService;
    }

    @Operation(summary = "Get connectors without download info for an integration method revision",
            description = "Returns connectors linked to an integration method revision that do NOT have " +
                    "download information (no artifactUrl set). These are connectors that were added but " +
                    "the Jenkins build was never triggered or did not complete successfully.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Connectors without download info retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Integration method revision not found")
    })
    @GetMapping("/connectors-without-download")
    public ResponseEntity<List<ConnectorWithoutDownloadDto>> getConnectorsWithoutDownload(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision) {
        List<ConnectorWithoutDownloadDto> connectors = applicationService.getConnectorsWithoutDownloadInfo(methodId, revision);
        return ResponseEntity.ok(connectors);
    }

    @Operation(summary = "Save integration method as new version")
    @PutMapping
    public ResponseEntity<String> editIntegrationMethod(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision,
            @RequestBody EditIntegrationMethodDto dto,
            Authentication authentication) {
        try {
            String newRevision = applicationService.editIntegrationMethod(methodId, revision, dto, authentication.getName());
            return ResponseEntity.ok(newRevision);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @Operation(summary = "Start review of an in-review integration method revision",
            description = "Moves an in-review revision to REVIEWING and locks it for editing until the "
                    + "review is resolved (approve, reject, or stop). Superuser only.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Review started"),
            @ApiResponse(responseCode = "404", description = "Integration method revision not found"),
            @ApiResponse(responseCode = "409", description = "Revision is not in review")
    })
    @PostMapping("/start-review")
    public ResponseEntity<Void> startReviewIntegrationMethod(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision,
            Authentication authentication) {
        try {
            applicationService.startReviewIntegrationMethod(methodId, revision, authentication.getName());
            return ResponseEntity.ok().build();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @Operation(summary = "Stop the review of a revision under review",
            description = "Moves a REVIEWING revision back to IN_REVIEW, clearing the reviewer and "
                    + "unlocking it for editing. Superuser only.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Review stopped"),
            @ApiResponse(responseCode = "404", description = "Integration method revision not found"),
            @ApiResponse(responseCode = "409", description = "Revision is not under review")
    })
    @PostMapping("/stop-review")
    public ResponseEntity<Void> stopReviewIntegrationMethod(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision,
            Authentication authentication) {
        try {
            applicationService.stopReviewIntegrationMethod(methodId, revision, authentication.getName());
            return ResponseEntity.ok().build();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (RuntimeException e) {
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
    @PostMapping("/publish")
    public ResponseEntity<Void> publishIntegrationMethod(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision,
            Authentication authentication) {
        try {
            applicationService.publishIntegrationMethod(methodId, revision, authentication.getName());
            return ResponseEntity.ok().build();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (RuntimeException e) {
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
    @PostMapping("/reject")
    public ResponseEntity<Void> rejectIntegrationMethod(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision,
            Authentication authentication) {
        try {
            applicationService.rejectIntegrationMethod(methodId, revision, authentication.getName());
            return ResponseEntity.ok().build();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @Operation(summary = "Add a connector to an integration method revision",
            description = "Links a connector (existing or newly created) to the given integration method revision.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Connector added successfully"),
            @ApiResponse(responseCode = "404", description = "Integration method or connector not found")
    })
    @PostMapping("/connectors")
    public ResponseEntity<String> addConnectorToIntegrationMethod(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision,
            @RequestBody AddConnectorDto dto,
            Authentication authentication) {
        try {
            // Returns the revision the connector landed on: the same revision for a mutable draft, or a
            // freshly forked draft revision when the source was a published (immutable) version.
            String savedRevision = applicationService.addConnectorToIntegrationMethod(appId, methodId, revision, dto, authentication.getName());
            return ResponseEntity.ok(savedRevision);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @Operation(summary = "List connectors of an integration method revision")
    @GetMapping("/connectors")
    public ResponseEntity<List<ImplementationListItemDto>> getConnectorsForIntegrationMethod(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision) {
        return ResponseEntity.ok(applicationService.getConnectorsForIntegrationMethod(methodId, revision));
    }

    @Operation(summary = "Update a connector of an integration method revision",
            description = "Replaces the fields of an existing connector (and its bundle / latest version) in place.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Connector updated successfully"),
            @ApiResponse(responseCode = "404", description = "Integration method or connector not found")
    })
    @PutMapping("/connectors/{connectorId}")
    public ResponseEntity<Void> updateConnector(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision,
            @PathVariable Integer connectorId,
            @RequestBody EditConnectorDto dto,
            Authentication authentication) {
        try {
            applicationService.updateConnector(methodId, revision, connectorId, dto, authentication.getName());
            return ResponseEntity.ok().build();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @Operation(summary = "Set the connector compatibility range for an integration method revision",
            description = "Updates the connector version range (min/max) that this integration method supports.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Compatibility updated successfully"),
            @ApiResponse(responseCode = "404", description = "Integration method or connector not found")
    })
    @PutMapping("/connectors/{connectorId}/compatibility")
    public ResponseEntity<Void> updateConnectorCompatibility(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision,
            @PathVariable Integer connectorId,
            @RequestBody UpdateConnectorCompatibilityDto dto,
            Authentication authentication) {
        try {
            applicationService.updateConnectorCompatibility(methodId, revision, connectorId,
                    dto.connectorVersionFrom(), dto.connectorVersionTo(), authentication.getName());
            return ResponseEntity.ok().build();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @Operation(summary = "Remove a connector from an integration method revision",
            description = "Unlinks a connector from the given integration method revision. The connector itself is left intact.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Connector removed successfully"),
            @ApiResponse(responseCode = "404", description = "Integration method or connector not found")
    })
    @DeleteMapping("/connectors/{connectorId}")
    public ResponseEntity<Void> deleteConnectorFromIntegrationMethod(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision,
            @PathVariable Integer connectorId,
            Authentication authentication) {
        try {
            applicationService.deleteConnectorFromIntegrationMethod(methodId, revision, connectorId, authentication.getName());
            return ResponseEntity.ok().build();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @Operation(summary = "Upload tutorial file for a specific integration method revision")
    @PostMapping(value = "/tutorial", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
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

    @Operation(summary = "List tutorial files for a specific integration method revision")
    @GetMapping("/tutorial")
    public ResponseEntity<List<String>> listTutorialFiles(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision) {
        return ResponseEntity.ok(tutorialStorageService.listTutorialFiles(methodId, revision));
    }

    @Operation(summary = "Download a single tutorial file for a specific integration method revision")
    @GetMapping("/tutorial/file")
    public ResponseEntity<byte[]> downloadTutorialFile(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision,
            @RequestParam("name") String name) {
        try {
            Path file = tutorialStorageService.resolveTutorialFile(methodId, revision, name);
            byte[] bytes = Files.readAllBytes(file);
            String contentType = Files.probeContentType(file);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, contentType != null ? contentType : MediaType.APPLICATION_OCTET_STREAM_VALUE)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                    .body(bytes);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (IOException ex) {
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
    @GetMapping("/bundle")
    public ResponseEntity<byte[]> downloadBundle(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision,
            HttpServletRequest request) {
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
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to build bundle: " + ex.getMessage(), ex);
        }
    }

    @Operation(summary = "Delete a single tutorial file for a specific integration method revision")
    @DeleteMapping("/tutorial/file")
    public ResponseEntity<Void> deleteTutorialFile(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision,
            @RequestParam("name") String name) {
        try {
            tutorialStorageService.deleteTutorialFile(methodId, revision, name);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to delete tutorial file: " + ex.getMessage(), ex);
        }
    }

    @Operation(summary = "Get the support ticket for a revision under review",
            description = "Returns the work package opened for the revision and whether its status "
                    + "allows the review to be approved. Restricted to the reviewer (superuser) and "
                    + "the submitting side (author or maintainer), identified by the authenticated caller.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Support ticket state"),
            @ApiResponse(responseCode = "403", description = "Not the reviewer or the submitting side"),
            @ApiResponse(responseCode = "404", description = "Integration method revision not found")
    })
    @GetMapping("/support-ticket")
    public ResponseEntity<SupportTicketDto> getSupportTicket(
            @PathVariable UUID appId,
            @PathVariable UUID methodId,
            @PathVariable String revision,
            Authentication authentication) {
        try {
            return ResponseEntity.ok(supportTicketService.describe(methodId, revision, authentication.getName()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
