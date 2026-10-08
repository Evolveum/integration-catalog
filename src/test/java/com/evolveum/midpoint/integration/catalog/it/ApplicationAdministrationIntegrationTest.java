/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.it;

import com.evolveum.midpoint.integration.catalog.security.CatalogRole;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Superuser-only changes to an application: its details and the featured flag.
 */
class ApplicationAdministrationIntegrationTest extends AbstractCatalogIntegrationTest {

    private static final RequestPostProcessor SUPERUSER = user("it-admin", CatalogRole.SUPERUSER);
    private static final RequestPostProcessor CONTRIBUTOR = user("it-contributor", CatalogRole.ORGANIZATION_CONTRIBUTOR);

    @Test
    void superuserUpdatesApplicationDetails() throws Exception {
        UUID applicationId = applicationId(createRequest(uniqueDisplayName("Details"), CONTRIBUTOR));
        String renamed = uniqueDisplayName("Renamed");

        mockMvc.perform(put("/api/applications/{id}", applicationId)
                        .with(SUPERUSER).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"%s\", \"description\": \"New description\"}".formatted(renamed)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/applications/{id}", applicationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value(renamed))
                .andExpect(jsonPath("$.description").value("New description"));
    }

    @Test
    void superuserTogglesFeatured() throws Exception {
        UUID applicationId = applicationId(createRequest(uniqueDisplayName("Featured"), CONTRIBUTOR));

        setFeatured(applicationId, true);
        mockMvc.perform(get("/api/applications/{id}", applicationId))
                .andExpect(jsonPath("$.tags[*].name", hasItem("featured")));

        setFeatured(applicationId, false);
        mockMvc.perform(get("/api/applications/{id}", applicationId))
                .andExpect(jsonPath("$.tags[*].name", not(hasItem("featured"))));
    }

    @Test
    void nonSuperuserCannotChangeApplications() throws Exception {
        UUID applicationId = applicationId(createRequest(uniqueDisplayName("Forbidden"), CONTRIBUTOR));

        mockMvc.perform(put("/api/applications/{id}/featured", applicationId)
                        .with(CONTRIBUTOR).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"featured\": true}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/applications/{id}", applicationId)
                        .with(CONTRIBUTOR).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\": \"Hijacked\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void featuringUnknownApplicationIsNotFound() throws Exception {
        mockMvc.perform(put("/api/applications/{id}/featured", UUID.randomUUID())
                        .with(SUPERUSER).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"featured\": true}"))
                .andExpect(status().isNotFound());
    }

    private void setFeatured(UUID applicationId, boolean featured) throws Exception {
        mockMvc.perform(put("/api/applications/{id}/featured", applicationId)
                        .with(SUPERUSER).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"featured\": %s}".formatted(featured)))
                .andExpect(status().isOk());
    }
}
