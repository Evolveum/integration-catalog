package com.evolveum.midpoint.integration.catalog.dto;

import com.evolveum.midpoint.integration.catalog.object.MaintainerType;

public record MaintainerDto(
        Long id,
        String username,
        String organizationName,
        MaintainerType category
) {}
