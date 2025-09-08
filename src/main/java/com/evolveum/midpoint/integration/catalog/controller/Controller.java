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

    @Operation(summary = "Get application by ID", description = "Fetches a single application by its UUID")
    @GetMapping("application/{id}")
    public ResponseEntity<Application> getApplication(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(applicationService.getApplication(id));
        } catch (RuntimeException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Get connector version by ID", description = "Fetches a connector version by its UUID")
    @GetMapping("connector-version/{id}")
    public ResponseEntity<ConnidVersion> getConnectorVersion(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(applicationService.getConnectorVersion(id));
        } catch (RuntimeException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Get all application tags", description = "Fetches a application tags")
    @GetMapping("application-tags")
    public ResponseEntity<List<ApplicationTag>> getApplicationTags() {
        try {
            return ResponseEntity.ok(applicationService.getApplicationTags());
        } catch (RuntimeException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Get all countries of origin", description = "Fetches a countries of origin")
    @GetMapping("countries-of-origin")
    public ResponseEntity<List<CountryOfOrigin>> getCountriesOfOrigin() {
        try {
            return ResponseEntity.ok(applicationService.getCountriesOfOrigin());
        } catch (RuntimeException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "", description = "")
    @PostMapping("/upload/connector")
    public ResponseEntity<String> uploadConnector(@RequestBody UploadForm uploadForm) {
        // FIXME remove try {
        try {
            return ResponseEntity.ok()
                    .body(applicationService.uploadConnector(
                            uploadForm.getApplication(),
                            uploadForm.getImplementation(),
                            uploadForm.getImplementationVersion(),
                            uploadForm.getFiles()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @Operation(summary = "", description = "")
    @PostMapping("/upload/continue/{oid}")
    public ResponseEntity successBuild(@RequestBody ContinueForm continueForm, @PathVariable UUID oid) {
        applicationService.successBuild(oid, continueForm);
        return ResponseEntity.ok(HttpStatus.OK);
    }

    @Operation(summary = "", description = "")
    @PostMapping("/upload/continue/fail/{oid}")
    public ResponseEntity failBuild(@RequestBody FailForm failForm, @PathVariable UUID oid) {
        applicationService.failBuild(oid, failForm);
        return ResponseEntity.ok(HttpStatus.OK);
    }

    @Operation(summary = "", description = "")
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

    @Operation(summary = "Search applications by parameters", description = "")
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

    @Operation(summary = "Search versions of connector by parameters", description = "")
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
}
