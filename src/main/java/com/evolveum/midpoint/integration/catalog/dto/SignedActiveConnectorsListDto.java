/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.util.List;

public record SignedActiveConnectorsListDto(
        String name,
        List<SignedActiveConnectorDto> signedConnectors
) {}
