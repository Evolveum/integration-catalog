/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.form;

import com.evolveum.midpoint.integration.catalog.object.Application;
import com.evolveum.midpoint.integration.catalog.object.ApplicationTag;
import com.evolveum.midpoint.integration.catalog.object.CountryOfOrigin;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Created by Dominik.
 */
@Getter
@Setter
public class SearchForm {

    // keyword could be name, displayName, description
    private String keyword;
    private Application.ApplicationLifecycleType lifecycleState;
    private ApplicationTag applicationTag;
    private CountryOfOrigin countryOfOrigin;

    private String maintainer;
    private UUID applicationId;
    private String systemVersion;
}


