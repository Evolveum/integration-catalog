/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.controller;

import com.evolveum.midpoint.integration.catalog.dto.SupportTicketDto;
import com.evolveum.midpoint.integration.catalog.service.SupportTicketService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Exposes the support-portal work package behind a revision's review. Separate from
 * {@link Controller} so the review dialog's one read does not widen that class's dependencies.
 */
@RestController
@RequestMapping("/api/applications/{appId}/integration-method/{methodId}/{revision}")
@Tag(name = "Support ticket", description = "Support portal work package backing a review")
public class SupportTicketController {

    private final SupportTicketService supportTicketService;

    public SupportTicketController(SupportTicketService supportTicketService) {
        this.supportTicketService = supportTicketService;
    }

    @Operation(summary = "Get the support ticket for a revision under review",
            description = "Returns the work package opened for the revision and whether its status "
                    + "allows the review to be approved. Restricted to the reviewer (superuser) and "
                    + "the submitting side (author or maintainer), identified by the username the "
                    + "caller passes in.")
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
            @RequestParam String username) {
        try {
            return ResponseEntity.ok(
                    supportTicketService.describe(methodId, revision, username));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
