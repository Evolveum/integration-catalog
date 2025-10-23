package com.evolveum.midpoint.integration.catalog.dto;

import com.evolveum.midpoint.integration.catalog.common.ItemFile;
import com.evolveum.midpoint.integration.catalog.object.Application;
import com.evolveum.midpoint.integration.catalog.object.Implementation;
import com.evolveum.midpoint.integration.catalog.object.ImplementationVersion;

import java.util.List;

/**
 * Created by Dominik.
 */
public record UploadImplementationDto(
        Application application,
        Implementation implementation,
        ImplementationVersion implementationVersion,
        List<ItemFile> files
) {
}
