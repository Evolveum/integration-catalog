/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.controller;

import com.evolveum.midpoint.integration.catalog.dto.ApiKeyDto;
import com.evolveum.midpoint.integration.catalog.dto.CreateApiKeyRequestDto;
import com.evolveum.midpoint.integration.catalog.dto.CreatedApiKeyDto;
import com.evolveum.midpoint.integration.catalog.service.ApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Personal API keys of the authenticated user. The value is returned once, by the endpoint that
 * creates it.
 */
@RestController
@RequestMapping("/api/api-keys")
@Tag(name = "API keys", description = "Personal API keys of the authenticated user")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Operation(summary = "List API keys", description = "Keys of the authenticated user, newest first")
    @GetMapping
    public ResponseEntity<List<ApiKeyDto>> list(@AuthenticationPrincipal OidcUser user) {
        return ResponseEntity.ok(apiKeyService.list(user));
    }

    @Operation(summary = "Create an API key",
            description = "Creates a key in Gravitee and returns its value - the only time it is shown")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The key and its value"),
            @ApiResponse(responseCode = "503", description = "Gravitee is not configured"),
            @ApiResponse(responseCode = "502", description = "Gravitee refused or could not be reached")
    })
    @PostMapping
    public ResponseEntity<CreatedApiKeyDto> create(@AuthenticationPrincipal OidcUser user,
                                                   @RequestBody CreateApiKeyRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(apiKeyService.create(user, request));
    }

    @Operation(summary = "Rotate an API key",
            description = "Replaces the key with a new one and returns its value - the only time it is shown. "
                    + "The old key keeps working for Gravitee's two-hour grace period and stays listed as replaced")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The new key and its value"),
            @ApiResponse(responseCode = "404", description = "No such key of this user"),
            @ApiResponse(responseCode = "409", description = "The key is revoked, expired or already replaced"),
            @ApiResponse(responseCode = "502", description = "Gravitee refused or could not be reached")
    })
    @PostMapping("/{id}/renew")
    public ResponseEntity<CreatedApiKeyDto> renew(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(apiKeyService.renew(user, id));
    }

    @Operation(summary = "Revoke an API key",
            description = "Stops the key working immediately; it stays listed as revoked. Other keys of its "
                    + "subscription (a key it replaced, or the one that replaced it) keep working")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Revoked, or already was"),
            @ApiResponse(responseCode = "404", description = "No such key of this user"),
            @ApiResponse(responseCode = "502", description = "Gravitee refused or could not be reached")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        apiKeyService.revoke(user, id);
        return ResponseEntity.noContent().build();
    }
}
