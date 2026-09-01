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
    // The build now belongs to a connector bundle version - one built artifact, which may carry
    // several connectors. These identify it.
    private String connectorBundleVersionId;
    private String connectorBundleVersionRevision;
    // Legacy: the single connector version a build used to be started for. Still accepted so a Jenkins
    // job that has not been updated yet keeps working - the bundle version is resolved through it.
    private String connectorVersionId;
    private String connectorVersionRevision;

}
