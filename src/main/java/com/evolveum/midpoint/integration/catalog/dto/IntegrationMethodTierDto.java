/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import com.evolveum.midpoint.integration.catalog.object.SupportTier;

import java.util.UUID;

/** One published integration method in the support-tier overview. */
public record IntegrationMethodTierDto(
        UUID applicationId,       // application.id
        String applicationName,   // application.display_name
        UUID methodId,            // integration_method.id
        String revision,          // integration_method.revision
        String methodName,        // integration_method.display_name
        SupportTier supportTier   // integration_method.support_tier, null = not tiered
) {}
