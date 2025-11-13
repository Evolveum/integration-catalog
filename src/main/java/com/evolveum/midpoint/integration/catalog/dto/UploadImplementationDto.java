/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import com.evolveum.midpoint.integration.catalog.common.ItemFile;
import com.evolveum.midpoint.integration.catalog.object.Application;
import com.evolveum.midpoint.integration.catalog.object.BundleVersion;
import com.evolveum.midpoint.integration.catalog.object.ConnectorBundle;
import com.evolveum.midpoint.integration.catalog.object.Implementation;
import com.evolveum.midpoint.integration.catalog.object.ImplementationVersion;

import java.util.List;

/**
 * Created by Dominik.
 */
public record UploadImplementationDto(
        Application application,
        Implementation implementation,
        ConnectorBundle connectorBundle,
        BundleVersion bundleVersion,
        ImplementationVersion implementationVersion,
        List<ItemFile> files
) {
}
