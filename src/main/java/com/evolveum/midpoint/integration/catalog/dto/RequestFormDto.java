/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * DTO for request form submissions that create both an Application and a Request.
 * Created by TomasS.
 */
public record RequestFormDto(
        @NotBlank String integrationApplicationName,
        String baseUrl,
        List<String> capabilities,
        @NotBlank String description,
        String systemVersion,
        String email,
        Boolean collab,
        String requester
) {}
