/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class VerifyBundleInformationForm {
    private String version;                     // connector_bundle_version.bundle_version
    private String className;                   // connector.fully_qualified_class_name
    private String bundleName;                  // connector_bundle.bundle_name
    private String integrationMethodRevision;   // integration_method.revision
    private String connectorBundleVersionId;
    private String connectorBundleVersionRevision;
    private String connectorVersionId;
    private String connectorVersionRevision;

}
