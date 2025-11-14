/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository.adapter;

import com.evolveum.midpoint.integration.catalog.object.ImplementationVersion.CapabilitiesType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Converter for CapabilitiesType array to PostgreSQL text representation
 */
@Converter
public class CapabilitiesArrayConverter implements AttributeConverter<CapabilitiesType[], String> {

    @Override
    public String convertToDatabaseColumn(CapabilitiesType[] attribute) {
        if (attribute == null || attribute.length == 0) {
            return null;
        }

        // Convert to PostgreSQL array literal format: {value1,value2,value3}
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < attribute.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(attribute[i].name());
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public CapabilitiesType[] convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return new CapabilitiesType[0];
        }

        // Remove surrounding braces
        String data = dbData.trim();
        if (data.startsWith("{") && data.endsWith("}")) {
            data = data.substring(1, data.length() - 1);
        }

        if (data.isEmpty()) {
            return new CapabilitiesType[0];
        }

        // Split by comma and convert to enum
        String[] parts = data.split(",");
        CapabilitiesType[] result = new CapabilitiesType[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = CapabilitiesType.valueOf(parts[i].trim());
        }
        return result;
    }
}
