/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.controller;

import com.evolveum.midpoint.integration.catalog.dto.EnvironmentDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (unauthenticated) endpoint exposing the deployment environment so the
 * frontend can decide whether to show the staging banner.
 */
@RestController
@RequestMapping("/api/environment")
@Tag(name = "Environment", description = "Deployment environment info")
public class EnvironmentController {

    private final String environment;

    public EnvironmentController(@Value("${catalog.environment:production}") String environment) {
        this.environment = environment;
    }

    @Operation(summary = "Get environment",
            description = "Returns the deployment environment (inStaging or production)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deployment environment")
    })
    @GetMapping
    public ResponseEntity<EnvironmentDto> getEnvironment() {
        return ResponseEntity.ok(new EnvironmentDto(environment));
    }
}
