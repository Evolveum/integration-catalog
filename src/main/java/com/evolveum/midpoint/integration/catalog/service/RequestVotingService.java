/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.RequestFormDto;
import com.evolveum.midpoint.integration.catalog.object.Application;
import com.evolveum.midpoint.integration.catalog.object.ImplementationVersion.CapabilitiesType;
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

/**
 * Service for managing connector requests and voting.
 * Handles creation of requests, vote submission, and vote counting.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequestVotingService {

    private final RequestRepository requestRepository;
    private final VoteRepository voteRepository;
    private final ApplicationRepository applicationRepository;
    private final ApplicationTagService applicationTagService;
    private final ObjectClassCapabilitiesRepository objectClassCapabilitiesRepository;

    // ==================== Request Methods ====================

    /**
     * Get all requests.
     */
    public List<Request> getRequests() {
        return requestRepository.findAll();
    }

    /**
     * Get a request by ID.
     */
    public Optional<Request> getRequest(Long id) {
        return requestRepository.findById(id);
    }

    /**
     * Get a request for a specific application.
     */
    public Optional<Request> getRequestForApplication(UUID appId) {
        return requestRepository.findByApplicationId(appId);
    }

    /**
     * Creates a new Application and Request from the request form submission.
     * The Application will be created with lifecycle state REQUESTED.
     *
     * @param dto RequestFormDto containing form data including structured capabilities per object class
     * @return The created Request entity
     */
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
                    CapabilitiesType[] capArray = caps.stream()
                            .map(CapabilitiesType::valueOf)
                            .toArray(CapabilitiesType[]::new);

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

    /**
     * Cancels a request and deletes its associated application.
     */
    @Transactional
    public void cancelRequest(Long requestId) {
        Request request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found: " + requestId));
        Application application = request.getApplication();
        requestRepository.delete(request);
        applicationRepository.delete(application);
    }

    // ==================== Voting Methods ====================

    /**
     * Get all votes.
     */
    public List<Vote> getVotes() {
        return voteRepository.findAll();
    }

    /**
     * Submit a vote for a request.
     * Each user can only vote once per request (enforced by unique constraint).
     *
     * @param requestId The ID of the request to vote for
     * @param voter The username of the voter
     * @return The created Vote entity
     * @throws IllegalArgumentException if request not found or user already voted
     */
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

    /**
     * Get the vote count for a specific request.
     *
     * @param requestId The ID of the request
     * @return The number of votes
     */
    public long getVoteCount(Long requestId) {
        return voteRepository.countByRequestId(requestId);
    }

    /**
     * Check if a user has voted for a specific request.
     *
     * @param requestId The ID of the request
     * @param voter The username of the voter
     * @return true if user has voted, false otherwise
     */
    public boolean hasUserVoted(Long requestId, String voter) {
        return voteRepository.existsByRequestIdAndVoter(requestId, voter);
    }
}
