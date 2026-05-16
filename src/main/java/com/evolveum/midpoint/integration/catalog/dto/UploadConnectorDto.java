/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import com.evolveum.midpoint.integration.catalog.object.BuildFrameworkType;
import com.evolveum.midpoint.integration.catalog.object.ConnectorBundle;

public record UploadConnectorDto(
        String displayName,
        ConnectorBundle.FrameworkType framework,
        String version,
        String bundleName,
        ConnectorBundle.LicenseType license,
        BuildFrameworkType buildFramework,
        String description,
        String maintainer,
        String browseLink,
        String ticketingSystemLink,
        String gitCloneUrl,
        String className,
        String pathToProject,
        String commitTag,
        String bundleDisplayName
) {
}
