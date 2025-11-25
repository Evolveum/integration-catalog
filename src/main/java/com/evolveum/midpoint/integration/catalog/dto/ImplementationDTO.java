package com.evolveum.midpoint.integration.catalog.dto;

import com.evolveum.midpoint.integration.catalog.object.BundleVersion;
import com.evolveum.midpoint.integration.catalog.object.ConnectorBundle;

import java.util.UUID;

public record ImplementationDTO(
        UUID implementationId,
        String displayName,
        ConnectorBundle.FrameworkType framework,
        String connectorVersion,
        String bundleName,
        ConnectorBundle.LicenseType license,
        BundleVersion.BuildFrameworkType buildFramework,
        String description,
        String maintainer,
        String browseLink,
        String ticketingSystemLink,
        String checkoutLink,
        String downloadLink,
        String connidVersion,
        String className,
        String pathToProject
) {
}