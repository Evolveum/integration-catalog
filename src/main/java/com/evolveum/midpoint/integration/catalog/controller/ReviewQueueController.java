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

/** The superuser's approval queue: what waits for a review decision across the whole catalog. */
@RestController
@RequestMapping("/api/review-queue")
@Tag(name = "Review queue", description = "Integration method revisions waiting for a superuser")
public class ReviewQueueController {

    private final MyItemsService myItemsService;

    public ReviewQueueController(MyItemsService myItemsService) {
        this.myItemsService = myItemsService;
    }

    @Operation(summary = "Approval queue",
            description = "Every integration method revision awaiting approval or under review — superuser only")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The waiting revisions, longest waiting first"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not a superuser")
    })
    @GetMapping
    public ResponseEntity<List<MyIntegrationMethodDto>> reviewQueue() {
        return ResponseEntity.ok(myItemsService.reviewQueue());
    }
}
