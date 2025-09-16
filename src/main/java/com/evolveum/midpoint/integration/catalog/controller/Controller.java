/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.controller;

import com.evolveum.midpoint.integration.catalog.dto.RequestDto;
import com.evolveum.midpoint.integration.catalog.form.ContinueForm;
import com.evolveum.midpoint.integration.catalog.form.FailForm;
import com.evolveum.midpoint.integration.catalog.form.SearchForm;
import com.evolveum.midpoint.integration.catalog.form.UploadForm;
import com.evolveum.midpoint.integration.catalog.object.*;

import com.evolveum.midpoint.integration.catalog.repository.DownloadsRepository;
import com.evolveum.midpoint.integration.catalog.repository.ImplementationVersionRepository;
import com.evolveum.midpoint.integration.catalog.repository.RequestRepository;
import com.evolveum.midpoint.integration.catalog.service.ApplicationService;

import com.evolveum.midpoint.integration.catalog.utils.Inet;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@Tag(name = "Integration catalog", description = "API for managing endpoints of Integration catalog")
public class Controller {

    private final ApplicationService applicationService;

    private final DownloadsRepository downloadsRepository;
    private final ImplementationVersionRepository implementationVersionRepository;
    private final RequestRepository requestRepository;

    public Controller(ApplicationService applicationService, DownloadsRepository downloadsRepository, ImplementationVersionRepository implementationVersionRepository, RequestRepository requestRepository) {
        this.applicationService = applicationService;
        this.downloadsRepository = downloadsRepository;
        this.implementationVersionRepository = implementationVersionRepository;
        this.requestRepository = requestRepository;
    }

    @Operation(summary = "Get application by ID",
            description = "Fetches a single application by its UUID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Application found"),
            @ApiResponse(responseCode = "404", description = "Application not found")
    })
    @GetMapping("application/{id}")
    public ResponseEntity<Application> getApplication(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(applicationService.getApplication(id));
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
    @GetMapping("connector-version/{id}")
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
    @GetMapping("application-tags")
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
    @GetMapping("countries-of-origin")
    public ResponseEntity<List<CountryOfOrigin>> getCountriesOfOrigin() {
        try {
            return ResponseEntity.ok(applicationService.getCountriesOfOrigin());
        } catch (RuntimeException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping(path = "/csv")
    public String createApplication() throws IOException, InterruptedException {
//        return applicationService.createConnector(
//                "csv",
//                Implementation.FrameworkType.CONNID,
//                "https://github.com/Evolveum/connector-csv/releases/tag/v2.8",
//                "https://github.com/Evolveum/connector-csv.git");

        return null;
    }

    @PostMapping(path = "/csv/error")
    public String createApplicationError() throws IOException, InterruptedException {
//        return applicationService.createConnector(
//                "csv",
//                Implementation.FrameworkType.CONNID,
//                "https://github.com/Evolveum/connector-csv/tree/testing/error",
//                "https://github.com/Evolveum/connector-csv.git");

        return null;
    }

    @PostMapping("/upload/continue/{oid}")
    public ResponseEntity successBuild(@RequestBody ContinueForm continueForm, @PathVariable UUID oid) {
        applicationService.successBuild(oid, continueForm);
        return ResponseEntity.ok(HttpStatus.OK);
    }

    @PostMapping("/upload/continue/fail/{oid}")
    public ResponseEntity failBuild(@RequestBody FailForm failForm, @PathVariable UUID oid) {
        applicationService.failBuild(oid, failForm);
        return ResponseEntity.ok(HttpStatus.OK);
    }

    @GetMapping("/download/{oid}")
    public ResponseEntity<byte[]> redirectToDownload(@PathVariable Integer oid, HttpServletRequest request) {
        ImplementationVersion version = implementationVersionRepository
                .findById(oid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Version not found"));

        try (InputStream in = new URL(version.getDownloadLink()).openStream()) {
            byte[] fileBytes = in.readAllBytes();
            String filename = version.getDownloadLink().substring(version.getDownloadLink().lastIndexOf('/') + 1);

            Inet ip = new Inet(request.getRemoteAddr());
            String ua = request.getHeader("User-Agent");
            OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(10);

            //get last record
            boolean duplicityCheck = downloadsRepository.existsRecentDuplicate(version, ip, ua, cutoff);

            //check for duplicity
            if (!duplicityCheck) {
                Downloads dl = new Downloads();
                dl.setImplementationVersion(version);
                dl.setIpAddress(ip);
                dl.setUserAgent(ua);
                dl.setDownloadedAt(OffsetDateTime.now());
                downloadsRepository.save(dl);
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(fileBytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @PostMapping("upload/scimrest")
    public ResponseEntity<String> uploadScimRestConnector(@RequestBody UploadForm uploadForm) {
        try {
            return ResponseEntity.ok()
                    .body(applicationService.createConnector(
                            uploadForm.getApplication(),
                            uploadForm.getImplementation(),
                            uploadForm.getImplementationVersion(),
                            uploadForm.getFiles()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(summary = "Search applications by parameters",
            description = "")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Applications found"),
            @ApiResponse(responseCode = "404", description = "Applications not found")
    })
    @PostMapping("application/search/{size}/{page}")
    public ResponseEntity<Page<Application>> searchApplication(
            @RequestBody SearchForm searchForm,
            @PathVariable int size,
            @PathVariable int page
    ) {
        try {
            return ResponseEntity.ok(applicationService.searchApplication(searchForm, size, page));
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
    @GetMapping("version-of-connector/search/{size}/{page}")
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

    @Operation(summary = "Get all votes",
            description = "Fetches votes")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Votes found"),
            @ApiResponse(responseCode = "404", description = "Votes not found")
    })
    @GetMapping("votes")
    public ResponseEntity<List<Votes>> getVotes() {
        try {
            return ResponseEntity.ok(applicationService.getVotes());
        } catch (RuntimeException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    /*@Operation(summary = "Get all requests",
            description = "Fetches requests")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Requests found"),
            @ApiResponse(responseCode = "404", description = "Requests not found")
    })
    @GetMapping("requests")
    public ResponseEntity<List<Request>> getRequest() {
        try {
            return ResponseEntity.ok(applicationService.getRequest());
        } catch (RuntimeException ex) {
            return ResponseEntity.notFound().build();
        }
    }*/

    @GetMapping("/requests/{id}")
    public ResponseEntity<RequestDto> getRequest(@PathVariable Long id) {
        Request request = requestRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        return ResponseEntity.ok(RequestDto.fromEntity(request));
    }

    @GetMapping("/requests")
    public List<RequestDto> getAllRequests() {
        return requestRepository.findAll()
                .stream()
                .map(RequestDto::fromEntity)
                .toList();
    }

    @GetMapping("/applications/{appId}/requests")
    public List<RequestDto> getRequestsForApplication(@PathVariable UUID appId) {
        return requestRepository.findByApplication_Id(appId).stream()
                .map(RequestDto::fromEntity)
                .toList();
    }
}
