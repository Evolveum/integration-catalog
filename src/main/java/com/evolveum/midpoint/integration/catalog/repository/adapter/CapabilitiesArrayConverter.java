/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository.adapter;

import com.evolveum.midpoint.integration.catalog.object.CapabilityType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Converter for CapabilityType array to PostgreSQL text representation
 */
@Converter
public class CapabilitiesArrayConverter implements AttributeConverter<CapabilityType[], String> {

    @Override
    public String convertToDatabaseColumn(CapabilityType[] attribute) {
        if (attribute == null || attribute.length == 0) {
            return null;
        }

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
    public CapabilityType[] convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return new CapabilityType[0];
        }

        String data = dbData.trim();
        if (data.startsWith("{") && data.endsWith("}")) {
            data = data.substring(1, data.length() - 1);
        }

        if (data.isEmpty()) {
            return new CapabilityType[0];
        }

        String[] parts = data.split(",");
        CapabilityType[] result = new CapabilityType[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = CapabilityType.valueOf(parts[i].trim());
        }
        return result;
    }
}
