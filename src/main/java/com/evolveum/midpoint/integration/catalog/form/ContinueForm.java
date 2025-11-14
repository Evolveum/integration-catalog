/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.form;

import com.evolveum.midpoint.integration.catalog.object.ImplementationVersion;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class ContinueForm {
    private String connectorBundle;
    private String connectorVersion;
    private Long publishTime;
    private String downloadLink;
    private String connectorClass;
    private List<ImplementationVersion.CapabilitiesType> capability;

}
