/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

public record IncludedConnectorDto(
        String className,       // connector.fully_qualified_class_name
        String displayName,     // connector.display_name
        String version,         // connector_bundle_version.bundle_version of the connector's current version
        String description      // connector.description
) {}
