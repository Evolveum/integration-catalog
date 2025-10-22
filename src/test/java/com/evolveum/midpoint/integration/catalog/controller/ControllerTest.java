/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.controller;

import com.evolveum.midpoint.integration.catalog.dto.CreateRequestDto;
import com.evolveum.midpoint.integration.catalog.form.ContinueForm;
import com.evolveum.midpoint.integration.catalog.form.FailForm;
import com.evolveum.midpoint.integration.catalog.form.ItemFile;
import com.evolveum.midpoint.integration.catalog.form.SearchForm;
import com.evolveum.midpoint.integration.catalog.form.UploadForm;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.evolveum.midpoint.integration.catalog.service.ApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.*;

@WebMvcTest(Controller.class)
class ControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ApplicationService applicationService;

    private UUID testApplicationId;
    private UUID testVersionId;
    private Application testApplication;
    private ConnidVersion testConnidVersion;
    private ImplementationVersion testImplementationVersion;
    private ApplicationTag testApplicationTag;
    private CountryOfOrigin testCountryOfOrigin;
    private Request testRequest;

    @BeforeEach
    void setUp() {
        testApplicationId = UUID.randomUUID();
        testVersionId = UUID.randomUUID();

        testApplication = new Application();
        testApplication.setId(testApplicationId);
        testApplication.setName("Test Application");
        testApplication.setDisplayName("Test App");

        testConnidVersion = new ConnidVersion();
        testConnidVersion.setVersion("1.0.0");
        testConnidVersion.setMidpointVersion(new String[]{"4.4", "4.8"});

        testImplementationVersion = new ImplementationVersion();
        testImplementationVersion.setId(testVersionId);
        testImplementationVersion.setDownloadLink("http://example.com/connector.jar");
        testImplementationVersion.setConnectorVersion("1.0.0");

        testApplicationTag = new ApplicationTag();
        testApplicationTag.setId(1L);
        testApplicationTag.setName("Test Tag");

        testCountryOfOrigin = new CountryOfOrigin();
        testCountryOfOrigin.setId(1L);
        testCountryOfOrigin.setName("Test Country");

        testRequest = new Request();
        testRequest.setId(1L);
        testRequest.setApplicationId(testApplicationId);
        testRequest.setCapabilitiesType(Request.CapabilitiesType.READ);
        testRequest.setRequester("test@example.com");
    }

    @Test
    void testGetApplication_Success() throws Exception {
        when(applicationService.getApplication(testApplicationId)).thenReturn(testApplication);

        mockMvc.perform(get("/api/application/{id}", testApplicationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(testApplicationId.toString()))
                .andExpect(jsonPath("$.name").value("Test Application"));

        verify(applicationService).getApplication(testApplicationId);
    }

    @Test
    void testGetApplication_NotFound() throws Exception {
        when(applicationService.getApplication(any(UUID.class)))
                .thenThrow(new RuntimeException("Application not found"));

        mockMvc.perform(get("/api/application/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());

        verify(applicationService).getApplication(any(UUID.class));
    }

    @Test
    void testGetConnectorVersion_Success() throws Exception {
        when(applicationService.getConnectorVersion(testVersionId)).thenReturn(testConnidVersion);

        mockMvc.perform(get("/api/connector-version/{id}", testVersionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.0.0"));

        verify(applicationService).getConnectorVersion(testVersionId);
    }

    @Test
    void testGetConnectorVersion_NotFound() throws Exception {
        when(applicationService.getConnectorVersion(any(UUID.class)))
                .thenThrow(new RuntimeException("Connector version not found"));

        mockMvc.perform(get("/api/connector-version/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());

        verify(applicationService).getConnectorVersion(any(UUID.class));
    }

    @Test
    void testGetApplicationTags_Success() throws Exception {
        List<ApplicationTag> tags = List.of(testApplicationTag);
        when(applicationService.getApplicationTags()).thenReturn(tags);

        mockMvc.perform(get("/api/application-tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Test Tag"));

        verify(applicationService).getApplicationTags();
    }

    @Test
    void testGetApplicationTags_NotFound() throws Exception {
        when(applicationService.getApplicationTags()).thenThrow(new RuntimeException());

        mockMvc.perform(get("/api/application-tags"))
                .andExpect(status().isNotFound());

        verify(applicationService).getApplicationTags();
    }

    @Test
    void testGetCountriesOfOrigin_Success() throws Exception {
        List<CountryOfOrigin> countries = List.of(testCountryOfOrigin);
        when(applicationService.getCountriesOfOrigin()).thenReturn(countries);

        mockMvc.perform(get("/api/countries-of-origin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Test Country"));

        verify(applicationService).getCountriesOfOrigin();
    }

    @Test
    void testGetCountriesOfOrigin_NotFound() throws Exception {
        when(applicationService.getCountriesOfOrigin()).thenThrow(new RuntimeException());

        mockMvc.perform(get("/api/countries-of-origin"))
                .andExpect(status().isNotFound());

        verify(applicationService).getCountriesOfOrigin();
    }

    @Test
    void testUploadConnector_Success() throws Exception {
        UploadForm uploadForm = new UploadForm();
        uploadForm.setApplication(testApplication);
        uploadForm.setImplementation(new Implementation());
        uploadForm.setImplementationVersion(testImplementationVersion);
        uploadForm.setFiles(new ArrayList<>());

        String checkoutLink = "https://github.com/test/repo";
        when(applicationService.uploadConnector(
                any(Application.class),
                any(Implementation.class),
                any(ImplementationVersion.class),
                anyList())).thenReturn(checkoutLink);

        mockMvc.perform(post("/api/upload/connector")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(uploadForm)))
                .andExpect(status().isOk())
                .andExpect(content().string(checkoutLink));

        verify(applicationService).uploadConnector(
                any(Application.class),
                any(Implementation.class),
                any(ImplementationVersion.class),
                anyList());
    }

    @Test
    void testUploadConnector_Failure() throws Exception {
        UploadForm uploadForm = new UploadForm();
        uploadForm.setApplication(testApplication);
        uploadForm.setImplementation(new Implementation());
        uploadForm.setImplementationVersion(testImplementationVersion);
        uploadForm.setFiles(new ArrayList<>());

        when(applicationService.uploadConnector(
                any(Application.class),
                any(Implementation.class),
                any(ImplementationVersion.class),
                anyList())).thenThrow(new RuntimeException("Upload failed"));

        mockMvc.perform(post("/api/upload/connector")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(uploadForm)))
                .andExpect(status().isInternalServerError());

        verify(applicationService).uploadConnector(
                any(Application.class),
                any(Implementation.class),
                any(ImplementationVersion.class),
                anyList());
    }

    @Test
    void testSuccessBuild() throws Exception {
        ContinueForm continueForm = new ContinueForm();
        continueForm.setConnectorBundle("test-bundle");
        continueForm.setConnectorVersion("1.0.0");
        continueForm.setDownloadLink("http://example.com/download");
        continueForm.setPublishTime(System.currentTimeMillis());

        doNothing().when(applicationService).successBuild(eq(testVersionId), any(ContinueForm.class));

        mockMvc.perform(post("/api/upload/continue/{oid}", testVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(continueForm)))
                .andExpect(status().isOk());

        verify(applicationService).successBuild(eq(testVersionId), any(ContinueForm.class));
    }

    @Test
    void testFailBuild() throws Exception {
        FailForm failForm = new FailForm();
        failForm.setErrorMessage("Build failed");

        doNothing().when(applicationService).failBuild(eq(testVersionId), any(FailForm.class));

        mockMvc.perform(post("/api/upload/continue/fail/{oid}", testVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(failForm)))
                .andExpect(status().isOk());

        verify(applicationService).failBuild(eq(testVersionId), any(FailForm.class));
    }

    @Test
    void testRedirectToDownload_Success() throws Exception {
        byte[] fileBytes = "test file content".getBytes();
        doReturn(Optional.of(testImplementationVersion))
                .when(applicationService).findImplementationVersion(testVersionId);
        doReturn(fileBytes)
                .when(applicationService).downloadConnector(any(UUID.class), anyString(), nullable(String.class));

        mockMvc.perform(get("/api/download/{oid}", testVersionId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(header().exists("Content-Disposition"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("filename=\"connector.jar\"")));

        verify(applicationService).findImplementationVersion(testVersionId);
        verify(applicationService).downloadConnector(any(UUID.class), anyString(), nullable(String.class));
    }

    @Test
    void testRedirectToDownload_VersionNotFound() throws Exception {
        doReturn(Optional.empty())
                .when(applicationService).findImplementationVersion(testVersionId);

        mockMvc.perform(get("/api/download/{oid}", testVersionId))
                .andExpect(status().isNotFound());

        verify(applicationService).findImplementationVersion(testVersionId);
        verify(applicationService, never()).downloadConnector(any(UUID.class), anyString(), nullable(String.class));
    }

    @Test
    void testRedirectToDownload_IOException() throws Exception {
        doReturn(Optional.of(testImplementationVersion))
                .when(applicationService).findImplementationVersion(testVersionId);
        doThrow(new IOException("Download failed"))
                .when(applicationService).downloadConnector(any(UUID.class), anyString(), nullable(String.class));

        mockMvc.perform(get("/api/download/{oid}", testVersionId))
                .andExpect(status().isInternalServerError())
                .andExpect(content().bytes(new byte[0]));  // Expect empty body on error

        verify(applicationService).findImplementationVersion(testVersionId);
        verify(applicationService).downloadConnector(any(UUID.class), anyString(), nullable(String.class));
    }

    @Test
    void testUploadScimRestConnector_Success() throws Exception {
        UploadForm uploadForm = new UploadForm();
        uploadForm.setApplication(testApplication);
        uploadForm.setImplementation(new Implementation());
        uploadForm.setImplementationVersion(testImplementationVersion);
        uploadForm.setFiles(new ArrayList<>());

        String checkoutLink = "https://github.com/test/scimrest-repo";
        when(applicationService.uploadConnector(
                any(Application.class),
                any(Implementation.class),
                any(ImplementationVersion.class),
                anyList())).thenReturn(checkoutLink);

        mockMvc.perform(post("/api/upload/scimrest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(uploadForm)))
                .andExpect(status().isOk())
                .andExpect(content().string(checkoutLink));

        verify(applicationService).uploadConnector(
                any(Application.class),
                any(Implementation.class),
                any(ImplementationVersion.class),
                anyList());
    }

    @Test
    void testUploadScimRestConnector_Failure() throws Exception {
        UploadForm uploadForm = new UploadForm();
        uploadForm.setApplication(testApplication);
        uploadForm.setImplementation(new Implementation());
        uploadForm.setImplementationVersion(testImplementationVersion);
        uploadForm.setFiles(new ArrayList<>());

        when(applicationService.uploadConnector(
                any(Application.class),
                any(Implementation.class),
                any(ImplementationVersion.class),
                anyList())).thenThrow(new RuntimeException("Upload failed"));

        mockMvc.perform(post("/api/upload/scimrest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(uploadForm)))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Upload failed")));

        verify(applicationService).uploadConnector(
                any(Application.class),
                any(Implementation.class),
                any(ImplementationVersion.class),
                anyList());
    }

    @Test
    void testSearchApplication_Success() throws Exception {
        SearchForm searchForm = new SearchForm();
        searchForm.setKeyword("test");

        Page<Application> page = new PageImpl<>(List.of(testApplication));
        when(applicationService.searchApplication(any(SearchForm.class), eq(10), eq(0)))
                .thenReturn(page);

        mockMvc.perform(post("/api/application/search/{size}/{page}", 10, 0)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(searchForm)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(testApplicationId.toString()));

        verify(applicationService).searchApplication(any(SearchForm.class), eq(10), eq(0));
    }

    @Test
    void testSearchApplication_NotFound() throws Exception {
        SearchForm searchForm = new SearchForm();
        searchForm.setKeyword("nonexistent");

        when(applicationService.searchApplication(any(SearchForm.class), anyInt(), anyInt()))
                .thenThrow(new RuntimeException());

        mockMvc.perform(post("/api/application/search/{size}/{page}", 10, 0)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(searchForm)))
                .andExpect(status().isNotFound());

        verify(applicationService).searchApplication(any(SearchForm.class), anyInt(), anyInt());
    }

    @Test
    void testSearchVersionsOfConnector_Success() throws Exception {
        SearchForm searchForm = new SearchForm();
        searchForm.setMaintainer("test-maintainer");

        Page<ImplementationVersion> page = new PageImpl<>(List.of(testImplementationVersion));
        when(applicationService.searchVersionsOfConnector(any(SearchForm.class), eq(0), eq(10)))
                .thenReturn(page);

        mockMvc.perform(get("/api/version-of-connector/search/{size}/{page}", 10, 0)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(searchForm)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(testVersionId.toString()));

        verify(applicationService).searchVersionsOfConnector(any(SearchForm.class), eq(0), eq(10));
    }

    @Test
    void testSearchVersionsOfConnector_NotFound() throws Exception {
        SearchForm searchForm = new SearchForm();

        when(applicationService.searchVersionsOfConnector(any(SearchForm.class), anyInt(), anyInt()))
                .thenThrow(new RuntimeException());

        mockMvc.perform(get("/api/version-of-connector/search/{size}/{page}", 10, 0)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(searchForm)))
                .andExpect(status().isNotFound());

        verify(applicationService).searchVersionsOfConnector(any(SearchForm.class), anyInt(), anyInt());
    }

    @Test
    void testGetRequest_Success() throws Exception {
        when(applicationService.getRequest(1L)).thenReturn(Optional.of(testRequest));

        mockMvc.perform(get("/api/requests/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.applicationId").value(testApplicationId.toString()));

        verify(applicationService).getRequest(1L);
    }

    @Test
    void testGetRequest_NotFound() throws Exception {
        when(applicationService.getRequest(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/requests/{id}", 999L))
                .andExpect(status().isNotFound());

        verify(applicationService).getRequest(999L);
    }

    @Test
    void testGetRequestsForApplication() throws Exception {
        List<Request> requests = List.of(testRequest);
        when(applicationService.getRequestsForApplication(testApplicationId)).thenReturn(requests);

        mockMvc.perform(get("/api/applications/{appId}/requests", testApplicationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].applicationId").value(testApplicationId.toString()));

        verify(applicationService).getRequestsForApplication(testApplicationId);
    }

    @Test
    void testCreateRequest_Success() throws Exception {
        CreateRequestDto dto = new CreateRequestDto(testApplicationId, "READ", "test@example.com");

        when(applicationService.createRequest(
                eq(testApplicationId),
                eq("READ"),
                eq("test@example.com")
        )).thenReturn(testRequest);

        mockMvc.perform(post("/api/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.capabilitiesType").value("READ"));

        verify(applicationService).createRequest(
                eq(testApplicationId),
                eq("READ"),
                eq("test@example.com")
        );
    }

    @Test
    void testCreateRequest_BadRequest() throws Exception {
        CreateRequestDto dto = new CreateRequestDto(testApplicationId, "INVALID_TYPE", "test@example.com");

        when(applicationService.createRequest(
                any(UUID.class),
                anyString(),
                anyString()
        )).thenThrow(new IllegalArgumentException("Invalid capabilitiesType"));

        mockMvc.perform(post("/api/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());

        verify(applicationService).createRequest(any(UUID.class), anyString(), anyString());
    }
}
