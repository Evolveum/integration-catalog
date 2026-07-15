/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

/**
 * DTO for the active connectors listing endpoint.
 * Contains a subset of ApplicationCardDto fields, excluding logo metadata,
 * lifecycle state (always ACTIVE), vote count, and request ID.
 */
public record ActiveConnectorDto(
        String className,               // connector.fully_qualified_class_name
        String version,                 // connector_bundle_version.bundle_version
        String bundle                   // connector_bundle.bundle_name
) {}
