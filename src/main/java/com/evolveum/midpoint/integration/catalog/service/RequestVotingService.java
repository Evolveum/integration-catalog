/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.IntegrationMethodCapabilityStateDto;
import com.evolveum.midpoint.integration.catalog.dto.IntegrationMethodObjectCapabilitiesDto;
import com.evolveum.midpoint.integration.catalog.dto.RequestFormDto;
import com.evolveum.midpoint.integration.catalog.object.Application;
import com.evolveum.midpoint.integration.catalog.object.CapabilityState;
import com.evolveum.midpoint.integration.catalog.object.CapabilityType;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodType;
import com.evolveum.midpoint.integration.catalog.object.ObjectClassCapabilities;
import com.evolveum.midpoint.integration.catalog.object.Request;
import com.evolveum.midpoint.integration.catalog.object.Vote;
import com.evolveum.midpoint.integration.catalog.repository.ApplicationRepository;
import com.evolveum.midpoint.integration.catalog.repository.IntegrationMethodTypeRepository;
import com.evolveum.midpoint.integration.catalog.repository.ObjectClassCapabilitiesRepository;
import com.evolveum.midpoint.integration.catalog.repository.RequestRepository;
import com.evolveum.midpoint.integration.catalog.repository.VoteRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RequestVotingService {

    private final RequestRepository requestRepository;
    private final VoteRepository voteRepository;
    private final ApplicationRepository applicationRepository;
    private final ApplicationTagService applicationTagService;
    private final ObjectClassCapabilitiesRepository objectClassCapabilitiesRepository;
    private final IntegrationMethodTypeRepository integrationMethodTypeRepository;

    public List<Request> getRequests() {
        return requestRepository.findAll();
    }

    public Optional<Request> getRequest(Long id) {
        return requestRepository.findById(id);
    }

    public Optional<Request> getRequestForApplication(UUID appId) {
        return requestRepository.findByApplicationId(appId);
    }

    /**
     * @param requester the authenticated username — taken from the security context, not from
     *                  the form, so the recorded requester (who may later cancel the request)
     *                  cannot be spoofed by the client
     */
    @Transactional
    public Request createRequestFromForm(RequestFormDto dto, String requester) {
        String integrationApplicationName = dto.integrationApplicationName();
        String description = dto.description();
        String deploymentType = dto.deploymentType();
        IntegrationMethodType integrationMethodType = dto.integrationMethodTypeId() == null ? null
                : integrationMethodTypeRepository.findById(dto.integrationMethodTypeId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Integration method type not found: " + dto.integrationMethodTypeId()));

        String abbreviatedName = integrationApplicationName.toLowerCase()
                .replaceAll("[^a-z0-9_]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");

        Optional<Application> existingApp = applicationRepository.findByName(abbreviatedName);
        if (existingApp.isPresent()) {
            abbreviatedName = abbreviatedName + "_" + System.currentTimeMillis();
        }

        try {
            Application application = new Application();
            application.setName(abbreviatedName);
            application.setDisplayName(integrationApplicationName);
            application.setDescription(description != null ? description : "");
            application.setLifecycleState(Application.ApplicationLifecycleType.REQUESTED);

            application = applicationRepository.save(application);

            if (deploymentType != null && !deploymentType.isEmpty()) {
                applicationTagService.saveDeploymentTags(application, deploymentType);
            }

            if (requestRepository.existsByApplicationId(application.getId())) {
                throw new IllegalStateException("A request already exists for application: " + application.getDisplayName());
            }

            Request request = new Request();
            request.setApplication(application);
            request.setRequester(requester);
            request.setMail(dto.contactEmail());
            request.setCollab(dto.openToCollaborate() != null && dto.openToCollaborate());
            request.setSystemVersion(dto.systemVersion());
            request.setIntegrationNeed(dto.integrationNeed());
            request.setIntegrationMethodType(integrationMethodType);

            request = requestRepository.save(request);

            if (dto.capabilities() != null) {
                for (IntegrationMethodObjectCapabilitiesDto entry : dto.capabilities()) {
                    if (entry.objectClass() == null || entry.objectClass().isBlank()
                            || entry.capabilities() == null || entry.capabilities().isEmpty()) {
                        continue;
                    }
                    ObjectClassCapabilities occ = new ObjectClassCapabilities();
                    occ.setRequest(request);
                    occ.setObjectName(entry.objectClass());
                    occ.setCapabilities(inState(entry.capabilities(), CapabilityState.YES));
                    occ.setUnsupportedCapabilities(inState(entry.capabilities(), CapabilityState.NO));
                    occ.setUnknownCapabilities(inState(entry.capabilities(), CapabilityState.UNKNOWN));
                    objectClassCapabilitiesRepository.save(occ);
                }
            }

            return request;
        } catch (IllegalStateException e) {
            log.warn("Duplicate request attempt: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Failed to create request for application: {}", integrationApplicationName, e);
            throw new RuntimeException("Failed to create request: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void cancelRequest(Long requestId) {
        Request request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found: " + requestId));
        Application application = request.getApplication();
        requestRepository.delete(request);
        applicationRepository.delete(application);
    }

    public List<Vote> getVotes() {
        return voteRepository.findAll();
    }

    public Vote submitVote(Long requestId, String voter) {
        Request request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found: " + requestId));

        if (voteRepository.existsByRequestIdAndVoter(requestId, voter)) {
            throw new IllegalArgumentException("User has already voted for this request");
        }

        Vote vote = new Vote();
        vote.setRequestId(requestId);
        vote.setVoter(voter);
        vote.setRequest(request);

        return voteRepository.save(vote);
    }

    public long getVoteCount(Long requestId) {
        return voteRepository.countByRequestId(requestId);
    }

    public boolean hasUserVoted(Long requestId, String voter) {
        return voteRepository.existsByRequestIdAndVoter(requestId, voter);
    }

    private static CapabilityType[] inState(List<IntegrationMethodCapabilityStateDto> capabilities,
                                            CapabilityState state) {
        return capabilities.stream()
                .filter(c -> c.state() == state)
                .map(c -> CapabilityType.valueOf(c.name()))
                .toArray(CapabilityType[]::new);
    }
}
