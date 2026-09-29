/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.it;

import com.evolveum.midpoint.integration.catalog.object.Application;
import com.evolveum.midpoint.integration.catalog.security.CatalogRole;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A request for a new integration, from filing through voting to cancelling.
 */
class RequestLifecycleIntegrationTest extends AbstractCatalogIntegrationTest {

    private static final RequestPostProcessor REQUESTER = user("it-requester", CatalogRole.INDIVIDUAL_CONTRIBUTOR);
    private static final RequestPostProcessor OTHER_CONTRIBUTOR = user("it-other", CatalogRole.ORGANIZATION_CONTRIBUTOR);
    private static final RequestPostProcessor VOTER = user("it-voter", CatalogRole.READ_ONLY);
    private static final RequestPostProcessor SUPERUSER = user("it-admin", CatalogRole.SUPERUSER);

    @Test
    void createdRequestIsStoredWithItsRequestedApplication() throws Exception {
        String displayName = uniqueDisplayName("Request");

        String created = createRequest(displayName, REQUESTER);
        long requestId = requestId(created);
        UUID applicationId = applicationId(created);

        mockMvc.perform(get("/api/requests/{id}", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requester").value("it-requester"))
                .andExpect(jsonPath("$.integrationNeed").value("Provision accounts"));
        mockMvc.perform(get("/api/applications/{appId}/request", applicationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(requestId));

        Application application = applicationRepository.findById(applicationId).orElseThrow();
        assertThat(application.getDisplayName()).isEqualTo(displayName);
        assertThat(application.getName()).startsWith(IT_PREFIX);
        assertThat(application.getLifecycleState()).isEqualTo(Application.ApplicationLifecycleType.REQUESTED);
    }

    @Test
    void requestWithoutRequiredFieldsIsRejected() throws Exception {
        mockMvc.perform(post("/api/requests")
                        .with(REQUESTER).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"integrationApplicationName\": \"\", \"description\": \"\", \"integrationNeed\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void eachUserVotesOnce() throws Exception {
        long requestId = requestId(createRequest(uniqueDisplayName("Vote"), REQUESTER));

        mockMvc.perform(post("/api/requests/{id}/vote", requestId).with(VOTER).with(csrf()))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/requests/{id}/vote", requestId).with(VOTER).with(csrf()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/requests/{id}/vote", requestId).with(OTHER_CONTRIBUTOR).with(csrf()))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/requests/{id}/votes/count", requestId))
                .andExpect(status().isOk())
                .andExpect(content().string("2"));
        mockMvc.perform(get("/api/requests/{id}/votes/check", requestId).with(VOTER))
                .andExpect(content().string("true"));
        mockMvc.perform(get("/api/requests/{id}/votes/check", requestId).with(REQUESTER))
                .andExpect(content().string("false"));
    }

    @Test
    void onlyTheRequesterOrASuperuserMayCancel() throws Exception {
        String mine = createRequest(uniqueDisplayName("Cancel own"), REQUESTER);
        String theirs = createRequest(uniqueDisplayName("Cancel any"), REQUESTER);

        mockMvc.perform(delete("/api/requests/{id}", requestId(mine)).with(OTHER_CONTRIBUTOR).with(csrf()))
                .andExpect(status().isForbidden());

        // A vote must not block the cancel: it goes together with the request.
        mockMvc.perform(post("/api/requests/{id}/vote", requestId(mine)).with(VOTER).with(csrf()))
                .andExpect(status().isCreated());
        mockMvc.perform(delete("/api/requests/{id}", requestId(mine)).with(REQUESTER).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/requests/{id}", requestId(theirs)).with(SUPERUSER).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/requests/{id}", requestId(mine))).andExpect(status().isNotFound());
        assertThat(applicationRepository.findById(applicationId(mine))).isEmpty();
        assertThat(applicationRepository.findById(applicationId(theirs))).isEmpty();
    }
}
