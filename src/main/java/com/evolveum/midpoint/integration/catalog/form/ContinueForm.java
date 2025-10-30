/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.form;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class ContinueForm {
    private String connectorBundle;
    private String connectorVersion;
    private Long publishTime;
    private String downloadLink;

}
