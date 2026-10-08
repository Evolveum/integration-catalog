/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.it;

import com.evolveum.midpoint.integration.catalog.object.Application;
import com.evolveum.midpoint.integration.catalog.repository.ApplicationRepository;
import com.evolveum.midpoint.integration.catalog.repository.RequestRepository;
import com.evolveum.midpoint.integration.catalog.security.CatalogRole;
import com.evolveum.midpoint.integration.catalog.service.RequestVotingService;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base of the tests that run the whole application against a real PostgreSQL (see
 * application-integration-test.properties). Keycloak is stubbed: callers sign in with
 * {@link #user}, which hands the security chain the same roles claim a real login carries.
 *
 * <p>Tests commit for real, so what they create is named with {@link #IT_PREFIX} and removed
 * after each test - also after a failed one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
public abstract class AbstractCatalogIntegrationTest {

    /** Applications created from a display name starting "IT " get a technical name starting with this. */
    protected static final String IT_PREFIX = "it_";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ApplicationRepository applicationRepository;

    @Autowired
    private RequestRepository requestRepository;

    @Autowired
    private RequestVotingService requestVotingService;

    @AfterEach
    void deleteTestData() {
        for (Application application : applicationRepository.findAll()) {
            if (application.getName() == null || !application.getName().startsWith(IT_PREFIX)) {
                continue;
            }
            requestRepository.findByApplicationId(application.getId()).ifPresentOrElse(
                    request -> requestVotingService.cancelRequest(request.getId()),
                    () -> applicationRepository.deleteById(application.getId()));
        }
    }

    /** A signed-in user with the given catalog role. */
    protected static RequestPostProcessor user(String username, CatalogRole role) {
        return oidcLogin()
                .idToken(token -> token
                        .subject(username)
                        .claim("preferred_username", username)
                        .claim("roles", List.of(role.getIdentifier())))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role.getIdentifier()));
    }

    /**
     * Files a request through the API, which also creates its application in state REQUESTED.
     *
     * @param displayName should start with "IT " so the cleanup finds it
     * @return the response body
     */
    protected String createRequest(String displayName, RequestPostProcessor requester) throws Exception {
        String body = """
                {"integrationApplicationName": "%s",
                 "description": "Created by an integration test",
                 "integrationNeed": "Provision accounts"}
                """.formatted(displayName);
        MvcResult result = mockMvc.perform(post("/api/requests")
                        .with(requester).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    protected static long requestId(String requestJson) {
        return ((Number) JsonPath.read(requestJson, "$.id")).longValue();
    }

    protected static UUID applicationId(String requestJson) {
        return UUID.fromString(JsonPath.read(requestJson, "$.application.id"));
    }

    /** A display name no earlier run can have left behind. */
    protected static String uniqueDisplayName(String label) {
        return "IT " + label + " " + UUID.randomUUID().toString().substring(0, 8);
    }
}
