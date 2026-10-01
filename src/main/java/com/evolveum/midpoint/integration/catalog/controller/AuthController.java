/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.controller;

import com.evolveum.midpoint.integration.catalog.dto.CurrentUserDto;
import com.evolveum.midpoint.integration.catalog.dto.MaintainerDto;
import com.evolveum.midpoint.integration.catalog.dto.MyItemsDto;
import com.evolveum.midpoint.integration.catalog.service.AuthService;
import com.evolveum.midpoint.integration.catalog.service.MyItemsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Identity endpoints. Authentication itself is handled by Spring Security's OIDC support:
 * the login flow starts at /oauth2/authorization/oidc and logout at /logout —
 * there is no password login here anymore.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Authenticated user identity endpoints")
public class AuthController {

    private final AuthService authService;
    private final MyItemsService myItemsService;

    public AuthController(AuthService authService, MyItemsService myItemsService) {
        this.authService = authService;
        this.myItemsService = myItemsService;
    }

    @Operation(summary = "Current user", description = "Returns the profile of the authenticated user")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated user profile"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @GetMapping("/me")
    public ResponseEntity<CurrentUserDto> me(@AuthenticationPrincipal OidcUser oidcUser,
                                             Authentication authentication) {
        return ResponseEntity.ok(authService.getCurrentUser(authentication.getName(), oidcUser));
    }

    @Operation(summary = "Current user's items",
            description = "Integration methods and connectors the authenticated user maintains, "
                    + "personally or through their organization")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The user's integration methods and connectors"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @GetMapping("/me/items")
    public ResponseEntity<MyItemsDto> myItems(@AuthenticationPrincipal OidcUser oidcUser,
                                              Authentication authentication) {
        return ResponseEntity.ok(myItemsService.myItems(authentication.getName(), oidcUser));
    }

    @Operation(summary = "Get all maintainers",
            description = "Returns every maintainer that can be chosen — superuser only")
    @GetMapping("/all-maintainers")
    public ResponseEntity<List<MaintainerDto>> getAllMaintainers() {
        return ResponseEntity.ok(authService.getAllMaintainers());
    }
}
