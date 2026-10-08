/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TriggerBuildForm {
    private String className;
    private String version;
    private String integrationMethodRevision;
    private String connectorVersionId;
    private String connectorVersionRevision;
}
