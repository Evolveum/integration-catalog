/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.time.LocalDate;
import java.util.List;

public record ImplementationVersionDto(
        String description,
        List<String> implementationTags,
        List<String> capabilities,
        String connectorVersion,
        String systemVersion,
        LocalDate releasedDate,
        String author,
        String lifecycleState,
        String downloadLink
) {}
