/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.controller;

import com.evolveum.midpoint.integration.catalog.dto.ApplicationDto;
import com.evolveum.midpoint.integration.catalog.dto.CreateRequestDto;
import com.evolveum.midpoint.integration.catalog.form.ContinueForm;
import com.evolveum.midpoint.integration.catalog.form.FailForm;
import com.evolveum.midpoint.integration.catalog.form.SearchForm;
import com.evolveum.midpoint.integration.catalog.form.UploadForm;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.evolveum.midpoint.integration.catalog.service.ApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the REST Controller.
 * Tests all endpoints using MockMvc and mocked ApplicationService.
 */
@WebMvcTest(Controller.class)
class ControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ApplicationService applicationService;

    private UUID testAppId;
    private UUID testVersionId;
    private UUID testConnidVersionId;
    private Application testApplication;
    private ImplementationVersion testImplementationVersion;
    private ConnidVersion testConnidVersion;
    private Request testRequest;

    @BeforeEach
    void setUp() {
        testAppId = UUID.randomUUID();
        testVersionId = UUID.randomUUID();
        testConnidVersionId = UUID.randomUUID();

        // Setup test Application
        testApplication = new Application();
        testApplication.setId(testAppId);
        testApplication.setDisplayName("Test Application");
        testApplication.setDescription("Test Description");
        testApplication.setLifecycleState(Application.ApplicationLifecycleType.ACTIVE);
        testApplication.setLastModified(OffsetDateTime.now());

        // Setup test ImplementationVersion
        testImplementationVersion = new ImplementationVersion();
        testImplementationVersion.setId(testVersionId);
        testImplementationVersion.setDescription("Test Version");
        testImplementationVersion.setDownloadLink("http://example.com/download/test.jar");
        testImplementationVersion.setLifecycleState(ImplementationVersion.ImplementationVersionLifecycleType.ACTIVE);

        // Setup test ConnidVersion
        testConnidVersion = new ConnidVersion();
        testConnidVersion.setVersion("1.5.0.0");

        // Setup test Request
        testRequest = new Request();
        testRequest.setId(1L);
        testRequest.setApplication(testApplication);
        testRequest.setCapabilitiesType(Request.CapabilitiesType.READ);
        testRequest.setRequester("test@example.com");
    }

    // ===== GET /api/application/{id} =====

    @Test
    void getApplicationShouldReturnApplicationWhenFound() throws Exception {
        when(applicationService.getApplication(testAppId)).thenReturn(testApplication);

        mockMvc.perform(get("/api/application/{id}", testAppId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(testAppId.toString()))
                .andExpect(jsonPath("$.displayName").value("Test Application"))
                .andExpect(jsonPath("$.description").value("Test Description"))
                .andExpect(jsonPath("$.lifecycleState").value("ACTIVE"));

        verify(applicationService).getApplication(testAppId);
    }

    @Test
    void getApplicationShouldReturnNotFoundWhenApplicationDoesNotExist() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        when(applicationService.getApplication(nonExistentId))
                .thenThrow(new IllegalArgumentException("Application not found"));

        mockMvc.perform(get("/api/application/{id}", nonExistentId))
                .andExpect(status().isNotFound());

        verify(applicationService).getApplication(nonExistentId);
    }

    // ===== GET /api/connector-version/{id} =====

    @Test
    void getConnectorVersionShouldReturnConnectorVersionWhenFound() throws Exception {
        when(applicationService.getConnectorVersion(testVersionId)).thenReturn(testConnidVersion);

        mockMvc.perform(get("/api/connector-version/{id}", testVersionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.5.0.0"));

        verify(applicationService).getConnectorVersion(testVersionId);
    }

    @Test
    void getConnectorVersionShouldReturnNotFoundWhenVersionDoesNotExist() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        when(applicationService.getConnectorVersion(nonExistentId))
                .thenThrow(new RuntimeException("Not found"));

        mockMvc.perform(get("/api/connector-version/{id}", nonExistentId))
                .andExpect(status().isNotFound());

        verify(applicationService).getConnectorVersion(nonExistentId);
    }

    // ===== GET /api/application-tags =====

    @Test
    void getApplicationTagsShouldReturnAllTags() throws Exception {
        ApplicationTag tag1 = new ApplicationTag();
        tag1.setId(1L);
        tag1.setName("TAG1");
        tag1.setDisplayName("Tag 1");

        ApplicationTag tag2 = new ApplicationTag();
        tag2.setId(2L);
        tag2.setName("TAG2");
        tag2.setDisplayName("Tag 2");

        List<ApplicationTag> tags = Arrays.asList(tag1, tag2);
        when(applicationService.getApplicationTags()).thenReturn(tags);

        mockMvc.perform(get("/api/application-tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("TAG1"))
                .andExpect(jsonPath("$[1].name").value("TAG2"));

        verify(applicationService).getApplicationTags();
    }

    @Test
    void getApplicationTagsShouldReturnNotFoundWhenExceptionOccurs() throws Exception {
        when(applicationService.getApplicationTags()).thenThrow(new RuntimeException("Error"));

        mockMvc.perform(get("/api/application-tags"))
                .andExpect(status().isNotFound());

        verify(applicationService).getApplicationTags();
    }

    // ===== GET /api/countries-of-origin =====

    @Test
    void getCountriesOfOriginShouldReturnAllCountries() throws Exception {
        CountryOfOrigin country1 = new CountryOfOrigin();
        country1.setId(1L);
        country1.setName("US");
        country1.setDisplayName("United States");

        CountryOfOrigin country2 = new CountryOfOrigin();
        country2.setId(2L);
        country2.setName("UK");
        country2.setDisplayName("United Kingdom");

        List<CountryOfOrigin> countries = Arrays.asList(country1, country2);
        when(applicationService.getCountriesOfOrigin()).thenReturn(countries);

        mockMvc.perform(get("/api/countries-of-origin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("US"))
                .andExpect(jsonPath("$[1].name").value("UK"));

        verify(applicationService).getCountriesOfOrigin();
    }

    @Test
    void getCountriesOfOriginShouldReturnNotFoundWhenExceptionOccurs() throws Exception {
        when(applicationService.getCountriesOfOrigin()).thenThrow(new RuntimeException("Error"));

        mockMvc.perform(get("/api/countries-of-origin"))
                .andExpect(status().isNotFound());

        verify(applicationService).getCountriesOfOrigin();
    }

    // ===== POST /api/upload/connector =====

    @Test
    void uploadConnectorShouldReturnOkWhenUploadSucceeds() throws Exception {
        UploadForm uploadForm = new UploadForm();
        uploadForm.setApplication(testApplication);
        uploadForm.setImplementation(new Implementation());
        uploadForm.setImplementationVersion(testImplementationVersion);
        uploadForm.setFiles(new ArrayList<>());

        String checkoutLink = "https://github.com/test/repo";
        when(applicationService.uploadConnector(any(), any(), any(), any())).thenReturn(checkoutLink);

        mockMvc.perform(post("/api/upload/connector")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(uploadForm)))
                .andExpect(status().isOk())
                .andExpect(content().string(checkoutLink));

        verify(applicationService).uploadConnector(any(), any(), any(), any());
    }

    @Test
    void uploadConnectorShouldReturnInternalServerErrorWhenUploadFails() throws Exception {
        UploadForm uploadForm = new UploadForm();
        uploadForm.setApplication(testApplication);
        uploadForm.setImplementation(new Implementation());
        uploadForm.setImplementationVersion(testImplementationVersion);
        uploadForm.setFiles(new ArrayList<>());

        when(applicationService.uploadConnector(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Upload failed"));

        mockMvc.perform(post("/api/upload/connector")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(uploadForm)))
                .andExpect(status().isInternalServerError());

        verify(applicationService).uploadConnector(any(), any(), any(), any());
    }

    // ===== POST /api/upload/continue/{oid} =====

    @Test
    void successBuildShouldReturnOkWhenContinueSucceeds() throws Exception {
        ContinueForm continueForm = new ContinueForm();
        continueForm.setConnectorBundle("test-bundle");
        continueForm.setConnectorVersion("1.0.0");
        continueForm.setDownloadLink("http://example.com/download");
        continueForm.setPublishTime(System.currentTimeMillis());

        doNothing().when(applicationService).successBuild(testVersionId, continueForm);

        mockMvc.perform(post("/api/upload/continue/{oid}", testVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(continueForm)))
                .andExpect(status().isOk());

        verify(applicationService).successBuild(eq(testVersionId), any(ContinueForm.class));
    }

    // ===== POST /api/upload/continue/fail/{oid} =====

    @Test
    void failBuildShouldReturnOkWhenFailProcessingSucceeds() throws Exception {
        FailForm failForm = new FailForm();
        failForm.setErrorMessage("Build failed due to compilation error");

        doNothing().when(applicationService).failBuild(testVersionId, failForm);

        mockMvc.perform(post("/api/upload/continue/fail/{oid}", testVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(failForm)))
                .andExpect(status().isOk());

        verify(applicationService).failBuild(eq(testVersionId), any(FailForm.class));
    }

    // ===== GET /api/download/{oid} =====

    @Test
    void redirectToDownloadShouldReturnFileWhenDownloadSucceeds() throws Exception {
        byte[] fileBytes = "test file content".getBytes();
        when(applicationService.findImplementationVersion(testVersionId))
                .thenReturn(Optional.of(testImplementationVersion));
        when(applicationService.downloadConnector(eq(testVersionId), anyString(), anyString()))
                .thenReturn(fileBytes);

        mockMvc.perform(get("/api/download/{oid}", testVersionId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"test.jar\""))
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(content().bytes(fileBytes));

        verify(applicationService).findImplementationVersion(testVersionId);
        verify(applicationService).downloadConnector(eq(testVersionId), anyString(), anyString());
    }

    @Test
    void redirectToDownloadShouldReturnNotFoundWhenVersionDoesNotExist() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        when(applicationService.findImplementationVersion(nonExistentId))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/download/{oid}", nonExistentId))
                .andExpect(status().isNotFound());

        verify(applicationService).findImplementationVersion(nonExistentId);
        verify(applicationService, never()).downloadConnector(any(), anyString(), anyString());
    }

    @Test
    void redirectToDownloadShouldReturnInternalServerErrorWhenDownloadFails() throws Exception {
        when(applicationService.findImplementationVersion(testVersionId))
                .thenReturn(Optional.of(testImplementationVersion));
        when(applicationService.downloadConnector(eq(testVersionId), anyString(), anyString()))
                .thenThrow(new RuntimeException("Download failed"));

        mockMvc.perform(get("/api/download/{oid}", testVersionId))
                .andExpect(status().isInternalServerError());

        verify(applicationService).findImplementationVersion(testVersionId);
        verify(applicationService).downloadConnector(eq(testVersionId), anyString(), anyString());
    }

    // ===== POST /api/upload/scimrest =====

    @Test
    void uploadScimRestConnectorShouldReturnOkWhenUploadSucceeds() throws Exception {
        UploadForm uploadForm = new UploadForm();
        uploadForm.setApplication(testApplication);
        uploadForm.setImplementation(new Implementation());
        uploadForm.setImplementationVersion(testImplementationVersion);
        uploadForm.setFiles(new ArrayList<>());

        String checkoutLink = "https://github.com/test/scimrest-repo";
        when(applicationService.uploadConnector(any(), any(), any(), any())).thenReturn(checkoutLink);

        mockMvc.perform(post("/api/upload/scimrest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(uploadForm)))
                .andExpect(status().isOk())
                .andExpect(content().string(checkoutLink));

        verify(applicationService).uploadConnector(any(), any(), any(), any());
    }

    @Test
    void uploadScimRestConnectorShouldReturnInternalServerErrorWhenUploadFails() throws Exception {
        UploadForm uploadForm = new UploadForm();
        uploadForm.setApplication(testApplication);
        uploadForm.setImplementation(new Implementation());
        uploadForm.setImplementationVersion(testImplementationVersion);
        uploadForm.setFiles(new ArrayList<>());

        when(applicationService.uploadConnector(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Upload failed"));

        mockMvc.perform(post("/api/upload/scimrest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(uploadForm)))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Upload failed")));

        verify(applicationService).uploadConnector(any(), any(), any(), any());
    }

    // ===== POST /api/application/search/{size}/{page} =====

    @Test
    void searchApplicationShouldReturnPagedResults() throws Exception {
        SearchForm searchForm = new SearchForm();
        searchForm.setKeyword("test");

        List<Application> applications = Arrays.asList(testApplication);
        Page<Application> page = new PageImpl<>(applications);

        when(applicationService.searchApplication(any(SearchForm.class), eq(10), eq(0)))
                .thenReturn(page);

        mockMvc.perform(post("/api/application/search/{size}/{page}", 10, 0)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(searchForm)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(testAppId.toString()));

        verify(applicationService).searchApplication(any(SearchForm.class), eq(10), eq(0));
    }

    @Test
    void searchApplicationShouldReturnNotFoundWhenExceptionOccurs() throws Exception {
        SearchForm searchForm = new SearchForm();

        when(applicationService.searchApplication(any(SearchForm.class), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("Search failed"));

        mockMvc.perform(post("/api/application/search/{size}/{page}", 10, 0)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(searchForm)))
                .andExpect(status().isNotFound());

        verify(applicationService).searchApplication(any(SearchForm.class), eq(10), eq(0));
    }

    // ===== GET /api/version-of-connector/search/{size}/{page} =====

    @Test
    void searchVersionsOfConnectorShouldReturnPagedResults() throws Exception {
        SearchForm searchForm = new SearchForm();
        searchForm.setMaintainer("test");

        List<ImplementationVersion> versions = Arrays.asList(testImplementationVersion);
        Page<ImplementationVersion> page = new PageImpl<>(versions);

        when(applicationService.searchVersionsOfConnector(any(SearchForm.class), eq(0), eq(10)))
                .thenReturn(page);

        mockMvc.perform(get("/api/version-of-connector/search/{size}/{page}", 10, 0)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(searchForm)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(testVersionId.toString()));

        verify(applicationService).searchVersionsOfConnector(any(SearchForm.class), eq(0), eq(10));
    }

    @Test
    void searchVersionsOfConnectorShouldReturnNotFoundWhenExceptionOccurs() throws Exception {
        SearchForm searchForm = new SearchForm();

        when(applicationService.searchVersionsOfConnector(any(SearchForm.class), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("Search failed"));

        mockMvc.perform(get("/api/version-of-connector/search/{size}/{page}", 10, 0)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(searchForm)))
                .andExpect(status().isNotFound());

        verify(applicationService).searchVersionsOfConnector(any(SearchForm.class), eq(0), eq(10));
    }

    // ===== GET /api/requests/{id} =====

    @Test
    void getRequestShouldReturnRequestWhenFound() throws Exception {
        when(applicationService.getRequest(1L)).thenReturn(Optional.of(testRequest));

        mockMvc.perform(get("/api/requests/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.requester").value("test@example.com"))
                .andExpect(jsonPath("$.capabilitiesType").value("READ"));

        verify(applicationService).getRequest(1L);
    }

    @Test
    void getRequestShouldReturnNotFoundWhenRequestDoesNotExist() throws Exception {
        when(applicationService.getRequest(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/requests/{id}", 999L))
                .andExpect(status().isNotFound());

        verify(applicationService).getRequest(999L);
    }

    // ===== GET /api/applications/{appId}/requests =====

    @Test
    void getRequestsForApplicationShouldReturnListOfRequests() throws Exception {
        List<Request> requests = Arrays.asList(testRequest);
        when(applicationService.getRequestsForApplication(testAppId)).thenReturn(requests);

        mockMvc.perform(get("/api/applications/{appId}/requests", testAppId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].requester").value("test@example.com"));

        verify(applicationService).getRequestsForApplication(testAppId);
    }

    @Test
    void getRequestsForApplicationShouldReturnEmptyListWhenNoRequests() throws Exception {
        when(applicationService.getRequestsForApplication(testAppId)).thenReturn(new ArrayList<>());

        mockMvc.perform(get("/api/applications/{appId}/requests", testAppId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(applicationService).getRequestsForApplication(testAppId);
    }

    // ===== POST /api/request =====

    @Test
    void createRequestShouldReturnCreatedRequestWhenValidData() throws Exception {
        CreateRequestDto dto = new CreateRequestDto(testAppId, "READ", "test@example.com");

        when(applicationService.createRequest(testAppId, "READ", "test@example.com"))
                .thenReturn(testRequest);

        mockMvc.perform(post("/api/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.requester").value("test@example.com"))
                .andExpect(jsonPath("$.capabilitiesType").value("READ"));

        verify(applicationService).createRequest(testAppId, "READ", "test@example.com");
    }

    @Test
    void createRequestShouldReturnBadRequestWhenInvalidData() throws Exception {
        CreateRequestDto dto = new CreateRequestDto(testAppId, "INVALID_TYPE", "test@example.com");

        when(applicationService.createRequest(testAppId, "INVALID_TYPE", "test@example.com"))
                .thenThrow(new IllegalArgumentException("Invalid capabilitiesType"));

        mockMvc.perform(post("/api/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());

        verify(applicationService).createRequest(testAppId, "INVALID_TYPE", "test@example.com");
    }

    // ===== GET /api/applications =====

    @Test
    void getAllApplicationsShouldReturnAllApplications() throws Exception {
        ApplicationDto dto1 = new ApplicationDto(
                testAppId,
                "Test App",
                "Description",
                null,
                null,
                "ACTIVE",
                OffsetDateTime.now(),
                null,
                null,
                null,
                null
        );

        List<ApplicationDto> applications = Arrays.asList(dto1);
        when(applicationService.getAllApplications()).thenReturn(applications);

        mockMvc.perform(get("/api/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(testAppId.toString()))
                .andExpect(jsonPath("$[0].displayName").value("Test App"))
                .andExpect(jsonPath("$[0].lifecycleState").value("ACTIVE"));

        verify(applicationService).getAllApplications();
    }

    @Test
    void getAllApplicationsShouldReturnEmptyListWhenNoApplications() throws Exception {
        when(applicationService.getAllApplications()).thenReturn(new ArrayList<>());

        mockMvc.perform(get("/api/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(applicationService).getAllApplications();
    }
}
