/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
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
        String downloadLink,
        String framework
) {}
