/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.controller;

import com.evolveum.midpoint.integration.catalog.configuration.LinksProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (unauthenticated) endpoint exposing the external document links the frontend shows.
 */
@RestController
@RequestMapping("/api/links")
@Tag(name = "Links", description = "External document links")
public class LinksController {

    private final LinksProperties linksProperties;

    public LinksController(LinksProperties linksProperties) {
        this.linksProperties = linksProperties;
    }

    @Operation(summary = "Get external links",
            description = "Returns the URLs of the external documents linked from the frontend (catalog.links.* properties)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "External document links")
    })
    @GetMapping
    public ResponseEntity<LinksProperties> getLinks() {
        return ResponseEntity.ok(linksProperties);
    }
}
