/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.controller;

import com.evolveum.midpoint.integration.catalog.configuration.FooterProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (unauthenticated) endpoint exposing the page footer's columns and links.
 */
@RestController
@RequestMapping("/api/footer")
@Tag(name = "Footer", description = "Page footer content")
public class FooterController {

    private final FooterProperties footerProperties;

    public FooterController(FooterProperties footerProperties) {
        this.footerProperties = footerProperties;
    }

    @Operation(summary = "Get footer content",
            description = "Returns the footer columns, social profile links and bottom links (catalog.footer.* properties)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Footer content")
    })
    @GetMapping
    public ResponseEntity<FooterProperties> getFooter() {
        return ResponseEntity.ok(footerProperties);
    }
}
