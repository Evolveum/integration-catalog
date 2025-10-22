/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.PendingRequestDisplayDto;
import com.evolveum.midpoint.integration.catalog.dto.PendingRequestDto;
import com.evolveum.midpoint.integration.catalog.object.PendingRequest;
import com.evolveum.midpoint.integration.catalog.repository.PendingRequestRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for handling pending integration requests.
 */
@Service
public class PendingRequestService {

    @Autowired
    private PendingRequestRepository pendingRequestRepository;

    @Autowired
    private ObjectMapper objectMapper;

    public PendingRequest submitRequest(PendingRequestDto dto) throws JsonProcessingException {
        PendingRequest request = new PendingRequest();

        request.setIntegrationApplicationName(dto.getIntegrationApplicationName());
        request.setBaseUrl(dto.getBaseUrl());

        // Convert capabilities list to JSON string
        if (dto.getCapabilities() != null && !dto.getCapabilities().isEmpty()) {
            request.setCapabilities(objectMapper.writeValueAsString(dto.getCapabilities()));
        }

        request.setDescription(dto.getDescription());
        request.setSystemVersion(dto.getSystemVersion());
        request.setEmail(dto.getEmail());

        // Convert boolean to "yes" or "no"
        request.setCollab(dto.getCollab() != null && dto.getCollab() ? "yes" : "no");

        request.setRequester(dto.getRequester());

        return pendingRequestRepository.save(request);
    }

    public List<PendingRequestDisplayDto> getAllPendingRequests() {
        return pendingRequestRepository.findAll().stream()
                .map(request -> new PendingRequestDisplayDto(
                        request.getId(),
                        request.getIntegrationApplicationName(),
                        request.getDescription(),
                        "REQUESTED",
                        true
                ))
                .collect(Collectors.toList());
    }
}
