/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.controller;

import com.evolveum.midpoint.integration.catalog.form.ContinueForm;
import com.evolveum.midpoint.integration.catalog.form.FailForm;
import com.evolveum.midpoint.integration.catalog.form.SearchForm;
import com.evolveum.midpoint.integration.catalog.form.UploadForm;
import com.evolveum.midpoint.integration.catalog.object.*;

import com.evolveum.midpoint.integration.catalog.service.ApplicationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@Tag(name = "Integration catalog", description = "API for managing endpoints of Integration catalog")
public class Controller {

    private final ApplicationService applicationService;

    public Controller(ApplicationService applicationService) {
        this.applicationService = applicationService;
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
    public ResponseEntity<byte[]> redirectToDownload(@PathVariable UUID oid) {
        ImplementationVersion version = applicationService.getImplementationVersion(oid);

        try (InputStream in = new URL(version.getDownloadLink()).openStream()) {
            byte[] fileBytes = in.readAllBytes();
            String filename = version.getDownloadLink().substring(version.getDownloadLink().lastIndexOf('/') + 1);

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
            @ApiResponse(responseCode = "404", description = "Votes found")
    })
    @GetMapping("votes")
    public ResponseEntity<List<Votes>> getVotes() {
        try {
            return ResponseEntity.ok(applicationService.getVotes());
        } catch (RuntimeException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Get all requests",
            description = "Fetches requests")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Requests found"),
            @ApiResponse(responseCode = "404", description = "Requests found")
    })
    @GetMapping("votes")
    public ResponseEntity<List<Request>> getRequest() {
        try {
            return ResponseEntity.ok(applicationService.getRequest());
        } catch (RuntimeException ex) {
            return ResponseEntity.notFound().build();
        }
    }
}
