/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.controller;

import com.evolveum.midpoint.integration.catalog.dto.MyIntegrationMethodDto;
import com.evolveum.midpoint.integration.catalog.service.MyItemsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Superuser-only list of every integration method revision in the catalog, whatever its state or maintainer. */
@RestController
@RequestMapping("/api/all-integration-methods")
@Tag(name = "All integration methods", description = "Every integration method revision, for a superuser")
public class AllIntegrationMethodsController {

    private final MyItemsService myItemsService;

    public AllIntegrationMethodsController(MyItemsService myItemsService) {
        this.myItemsService = myItemsService;
    }

    @Operation(summary = "All integration methods",
            description = "Every revision of every integration method, grouped by method, newest revision first — superuser only")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every revision"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not a superuser")
    })
    @GetMapping
    public ResponseEntity<List<MyIntegrationMethodDto>> allIntegrationMethods() {
        return ResponseEntity.ok(myItemsService.allIntegrationMethods());
    }
}
