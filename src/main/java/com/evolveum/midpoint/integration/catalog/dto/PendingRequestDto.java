/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * DTO for submitting a pending integration request.
 */
@Getter @Setter
public class PendingRequestDto {
    private String integrationApplicationName;
    private String baseUrl;
    private List<String> capabilities;
    private String description;
    private String systemVersion;
    private String email;
    private Boolean collab;
    private String requester;
}
