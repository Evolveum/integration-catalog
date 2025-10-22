/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO for displaying pending requests as tiles.
 */
@Getter @Setter
@AllArgsConstructor
public class PendingRequestDisplayDto {
    private Integer id;
    private String displayName;
    private String description;
    private String lifecycleState;
    private boolean pendingRequest;
}
