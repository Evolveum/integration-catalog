/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.controller;

import com.evolveum.midpoint.integration.catalog.dto.MyConnectorDto;
import com.evolveum.midpoint.integration.catalog.dto.SupportTierDto;
import com.evolveum.midpoint.integration.catalog.service.ApplicationService;
import com.evolveum.midpoint.integration.catalog.service.MyItemsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Superuser-only list of every connector in the catalog, whoever maintains it. */
@RestController
@RequestMapping("/api/all-connectors")
@Tag(name = "All connectors", description = "Every connector with its versions and usage, for a superuser")
public class AllConnectorsController {

    private final MyItemsService myItemsService;
    private final ApplicationService applicationService;

    public AllConnectorsController(MyItemsService myItemsService, ApplicationService applicationService) {
        this.myItemsService = myItemsService;
        this.applicationService = applicationService;
    }

    @Operation(summary = "All connectors",
            description = "Every connector with its versions and the integration methods using each — superuser only")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every connector"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not a superuser")
    })
    @GetMapping
    public ResponseEntity<List<MyConnectorDto>> allConnectors() {
        return ResponseEntity.ok(myItemsService.allConnectors());
    }

    @Operation(summary = "Set or clear the support tier of a connector bundle version, all revisions — superuser only")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tier set"),
            @ApiResponse(responseCode = "403", description = "Not a superuser"),
            @ApiResponse(responseCode = "404", description = "No such bundle version")
    })
    @PutMapping("/bundle-versions/{bundleVersionId}/tier")
    public ResponseEntity<Void> setSupportTier(@PathVariable Integer bundleVersionId, @RequestBody SupportTierDto dto) {
        applicationService.setSupportTier(bundleVersionId, dto.tier());
        return ResponseEntity.ok().build();
    }
}
