/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

/**
 * Payload for the "Set up connector compatibility" modal: the connector version range (X.Y.Z) that a
 * given integration-method revision supports. {@code connectorVersionFrom} is required; a blank/null
 * {@code connectorVersionTo} means the method is expected to stay compatible with future releases.
 */
public record UpdateConnectorCompatibilityDto(
        String connectorVersionFrom,   // integration_method_connector.connector_minversion
        String connectorVersionTo      // integration_method_connector.connector_maxversion
) {}
