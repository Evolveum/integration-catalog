/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.form;

import com.evolveum.midpoint.integration.catalog.object.CapabilityType;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class ContinueForm {
    private String connectorBundle;
    private String connectorVersion;
    private String integrationMethodRevision;
    private Long publishTime;
    private String downloadLink;
    private String connectorClass;
    private List<CapabilityType> capability;
    // The build now belongs to a connector bundle version - one built artifact, which may carry
    // several connectors. These identify it.
    private String connectorBundleVersionId;
    private String connectorBundleVersionRevision;
    // Legacy: the single connector version a build used to be started for. Still accepted so a Jenkins
    // job that has not been updated yet keeps working - the bundle version is resolved through it.
    private String connectorVersionId;
    private String connectorVersionRevision;
}
