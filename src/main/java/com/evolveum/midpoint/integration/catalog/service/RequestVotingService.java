/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.RequestFormDto;
import com.evolveum.midpoint.integration.catalog.object.Application;
import com.evolveum.midpoint.integration.catalog.object.CapabilityType;
import com.evolveum.midpoint.integration.catalog.object.ObjectClassCapabilities;
import com.evolveum.midpoint.integration.catalog.object.Request;
import com.evolveum.midpoint.integration.catalog.object.Vote;
import com.evolveum.midpoint.integration.catalog.repository.ApplicationRepository;
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

    public List<Request> getRequests() {
        return requestRepository.findAll();
    }

    public Optional<Request> getRequest(Long id) {
        return requestRepository.findById(id);
    }

    public Optional<Request> getRequestForApplication(UUID appId) {
        return requestRepository.findByApplicationId(appId);
    }

    @Transactional
    public Request createRequestFromForm(RequestFormDto dto) {
        String integrationApplicationName = dto.integrationApplicationName();
        String description = dto.description();
        String deploymentType = dto.deploymentType();
        String requester = dto.requester();

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

            request = requestRepository.save(request);

            if (dto.capabilities() != null) {
                for (RequestFormDto.ObjectClassCapabilityEntry entry : dto.capabilities()) {
                    if (entry.objectName() == null || entry.objectName().isBlank()) {
                        continue;
                    }
                    List<String> caps = entry.capabilities();
                    if (caps == null || caps.isEmpty()) {
                        continue;
                    }
                    CapabilityType[] capArray = caps.stream()
                            .map(CapabilityType::valueOf)
                            .toArray(CapabilityType[]::new);

                    ObjectClassCapabilities occ = new ObjectClassCapabilities();
                    occ.setRequest(request);
                    occ.setObjectName(entry.objectName());
                    occ.setCapabilities(capArray);
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
}
