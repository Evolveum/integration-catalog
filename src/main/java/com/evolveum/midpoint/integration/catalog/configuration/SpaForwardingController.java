/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller that forwards all non-API routes to the Angular SPA.
 * This ensures that Angular routing works correctly when users refresh
 * the page or access routes directly.
 */
@Controller
public class SpaForwardingController {

    /**
     * Forward Angular routes to index.html so client-side routing works on page refresh.
     */
    @GetMapping(value = {"/applications", "/applications/{id}"})
    public String forwardApplications() {
        return "forward:/index.html";
    }
}
