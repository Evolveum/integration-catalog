package com.evolveum.midpoint.integration.catalog.dto;

import com.evolveum.midpoint.integration.catalog.object.BuildFrameworkType;
import com.evolveum.midpoint.integration.catalog.object.ConnectorBundle;

import java.util.UUID;

public record ImplementationDTO(
        UUID implementationId,
        String displayName,
        ConnectorBundle.FrameworkType framework,
        String connectorVersion,
        String bundleName,
        ConnectorBundle.LicenseType license,
        BuildFrameworkType buildFramework,
        String description,
        String maintainer,
        String browseLink,
        String ticketingSystemLink,
        String gitCloneUrl,
        String className,
        String pathToProject
) {
}
