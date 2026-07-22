/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.controller;

import com.evolveum.midpoint.integration.catalog.dto.*;
import com.evolveum.midpoint.integration.catalog.form.ContinueForm;
import com.evolveum.midpoint.integration.catalog.form.FailForm;
import com.evolveum.midpoint.integration.catalog.form.SearchForm;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.evolveum.midpoint.integration.catalog.service.ApplicationService;
import com.evolveum.midpoint.integration.catalog.service.TutorialStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.time.LocalDateTime;
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

    @MockitoBean
    private ApplicationService applicationService;

    @MockitoBean
    private com.evolveum.midpoint.integration.catalog.mapper.ApplicationMapper applicationMapper;

    @MockitoBean
    private com.evolveum.midpoint.integration.catalog.repository.ApplicationRepository applicationRepository;

    @MockitoBean
    private com.evolveum.midpoint.integration.catalog.repository.RequestRepository requestRepository;

    @MockitoBean
    private com.evolveum.midpoint.integration.catalog.repository.VoteRepository voteRepository;

    @MockitoBean
    private com.evolveum.midpoint.integration.catalog.service.LogoStorageService logoStorageService;

    @MockitoBean
    private TutorialStorageService tutorialStorageService;

    @MockitoBean
    private com.evolveum.midpoint.integration.catalog.repository.DownloadRepository downloadRepository;

    private UUID testAppId;
    private UUID testVersionId;
    private Application testApplication;
    private Request testRequest;
    private Vote testVote;

    @BeforeEach
    void setUp() {
        testAppId = UUID.randomUUID();
        testVersionId = UUID.randomUUID();

        // Setup test Application
        testApplication = new Application();
        testApplication.setId(testAppId);
        testApplication.setName("test_app");
        testApplication.setDisplayName("Test Application");
        testApplication.setDescription("Test Description");
        testApplication.setLifecycleState(Application.ApplicationLifecycleType.ACTIVE);
        testApplication.setCreatedAt(LocalDateTime.now());
        testApplication.setUpdated(LocalDateTime.now());

        // Setup test Request
        testRequest = new Request();
        testRequest.setId(1L);
        testRequest.setApplication(testApplication);
        testRequest.setRequester("test@example.com");

        // Setup test Vote
        testVote = new Vote();
        testVote.setRequestId(1L);
        testVote.setVoter("voter@example.com");
    }

    // ===== GET /api/applications/{id} =====

    @Test
    void getApplicationShouldReturnApplicationWhenExists() throws Exception {
        ApplicationDto dto = ApplicationDto.builder()
                .id(testAppId)
                .displayName("Test Application")
                .description("Test Description")
                .lifecycleState("ACTIVE")
                .createdAt(LocalDateTime.now())
                .updated(LocalDateTime.now())
                .build();

        when(applicationService.getApplication(testAppId)).thenReturn(testApplication);
        when(applicationMapper.mapToApplicationDto(testApplication)).thenReturn(dto);

        mockMvc.perform(get("/api/applications/{id}", testAppId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(testAppId.toString()))
                .andExpect(jsonPath("$.displayName").value("Test Application"));

        verify(applicationService).getApplication(testAppId);
        verify(applicationMapper).mapToApplicationDto(testApplication);
    }

    @Test
    void getApplicationShouldReturnNotFoundWhenNotExists() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        when(applicationService.getApplication(nonExistentId))
                .thenThrow(new RuntimeException("Application not found with id: " + nonExistentId));

        mockMvc.perform(get("/api/applications/{id}", nonExistentId))
                .andExpect(status().isInternalServerError());

        verify(applicationService).getApplication(nonExistentId);
    }

    // ===== GET /api/connid-versions/{id} — endpoint removed after DB rework =====

    @Disabled("Endpoint /api/connid-versions/{id} was removed in the DB rework")
    @Test
    void getConnectorVersionShouldReturnVersionWhenExists() throws Exception {
    }

    @Disabled("Endpoint /api/connid-versions/{id} was removed in the DB rework")
    @Test
    void getConnectorVersionShouldReturnNotFoundWhenNotExists() throws Exception {
    }

    // ===== GET /api/application-tags =====

    @Test
    void getApplicationTagsShouldReturnAllTags() throws Exception {
        ApplicationTagDto tag1 = new ApplicationTagDto(1L, "category_ldap", "LDAP", "CATEGORY");
        ApplicationTagDto tag2 = new ApplicationTagDto(2L, "category_hr", "HR Systems", "CATEGORY");

        List<ApplicationTagDto> tags = Arrays.asList(tag1, tag2);
        when(applicationService.getApplicationTags()).thenReturn(tags);

        mockMvc.perform(get("/api/application-tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("category_ldap"))
                .andExpect(jsonPath("$[1].displayName").value("HR Systems"));

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
        country2.setName("CZ");
        country2.setDisplayName("Czech Republic");

        List<CountryOfOrigin> countries = Arrays.asList(country1, country2);
        when(applicationService.getCountriesOfOrigin()).thenReturn(countries);

        mockMvc.perform(get("/api/countries-of-origin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("US"))
                .andExpect(jsonPath("$[1].displayName").value("Czech Republic"));

        verify(applicationService).getCountriesOfOrigin();
    }

    // ===== POST /api/upload/continue/{oid} =====

    @Test
    void completeBuildSuccessfullyShouldReturnOkWhenSuccessful() throws Exception {
        ContinueForm continueForm = new ContinueForm();
        continueForm.setConnectorBundle("test-bundle");
        continueForm.setConnectorVersion("1.0.0");
        continueForm.setDownloadLink("http://example.com/download");
        continueForm.setPublishTime(System.currentTimeMillis());
        continueForm.setConnectorClass("com.evolveum.polygon.connector.test.TestConnector");
        continueForm.setCapability(
                List.of(CapabilityType.SCHEMA,
                        CapabilityType.TEST,
                        CapabilityType.VALIDATE,
                        CapabilityType.GET,
                        CapabilityType.SEARCH
                ));

        doNothing().when(applicationService).successBuild(eq(testVersionId), any(ContinueForm.class));

        mockMvc.perform(post("/api/upload/continue/{oid}", testVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(continueForm)))
                .andExpect(status().isOk());

        verify(applicationService).successBuild(eq(testVersionId), any(ContinueForm.class));
    }

    // ===== POST /api/upload/continue/fail/{oid} =====

    @Test
    void completeBuildWithFailureShouldReturnOkWhenSuccessful() throws Exception {
        FailForm failForm = new FailForm();
        failForm.setErrorMessage("Build failed due to compilation error");

        doNothing().when(applicationService).failBuild(eq(testVersionId), any(FailForm.class));

        mockMvc.perform(post("/api/upload/continue/fail/{oid}", testVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(failForm)))
                .andExpect(status().isOk());

        verify(applicationService).failBuild(eq(testVersionId), any(FailForm.class));
    }

    // ===== POST /api/applications/search/{size}/{page} =====

    @Test
    void searchApplicationShouldReturnPagedResults() throws Exception {
        SearchForm searchForm = new SearchForm();
        searchForm.setKeyword("test");

        Page<Application> page = new PageImpl<>(Collections.singletonList(testApplication));
        when(applicationService.searchApplication(any(SearchForm.class), eq(0), eq(10)))
                .thenReturn(page);

        mockMvc.perform(post("/api/applications/search/{size}/{page}", 10, 0)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(searchForm)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(testAppId.toString()));

        verify(applicationService).searchApplication(any(SearchForm.class), eq(0), eq(10));
    }

    // ===== GET /api/requests/{id} =====

    @Test
    void getRequestShouldReturnRequestWhenExists() throws Exception {
        when(applicationService.getRequest(1L)).thenReturn(Optional.of(testRequest));

        mockMvc.perform(get("/api/requests/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.requester").value("test@example.com"));

        verify(applicationService).getRequest(1L);
    }

    @Test
    void getRequestShouldReturnNotFoundWhenNotExists() throws Exception {
        when(applicationService.getRequest(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/requests/{id}", 999L))
                .andExpect(status().isNotFound());

        verify(applicationService).getRequest(999L);
    }

    // ===== GET /api/applications/{appId}/request =====

    @Test
    void getRequestForApplicationShouldReturnRequest() throws Exception {
        when(applicationService.getRequestForApplication(testAppId)).thenReturn(Optional.of(testRequest));

        mockMvc.perform(get("/api/applications/{appId}/request", testAppId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.requester").value("test@example.com"));

        verify(applicationService).getRequestForApplication(testAppId);
    }

    @Test
    void getRequestForApplicationShouldReturnNotFoundWhenNotExists() throws Exception {
        when(applicationService.getRequestForApplication(testAppId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/applications/{appId}/request", testAppId))
                .andExpect(status().isNotFound());

        verify(applicationService).getRequestForApplication(testAppId);
    }

    // ===== POST /api/request =====

    @Test
    void createRequestShouldReturnCreatedWhenValid() throws Exception {
        RequestFormDto dto = new RequestFormDto(
                "Slack",
                "cloud-based",
                "Slack integration for team communication",
                "1.0",
                "test@example.com",
                false,
                "Test User",
                List.of(new RequestFormDto.ObjectClassCapabilityEntry("global", List.of("GET", "SEARCH")))
        );

        when(applicationService.createRequestFromForm(any(RequestFormDto.class)))
                .thenReturn(testRequest);

        mockMvc.perform(post("/api/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));

        verify(applicationService).createRequestFromForm(any(RequestFormDto.class));
    }

    @Test
    void createRequestShouldReturnBadRequestWhenInvalid() throws Exception {
        RequestFormDto dto = new RequestFormDto(
                "", // Empty name - invalid
                null,
                "", // Empty description - invalid
                null,
                null,
                null,
                null,
                null
        );

        mockMvc.perform(post("/api/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());

        verify(applicationService, never()).createRequestFromForm(any(RequestFormDto.class));
    }

    // ===== POST /api/requests/{requestId}/vote =====

    @Test
    void submitVoteShouldReturnCreatedWhenSuccessful() throws Exception {
        when(applicationService.submitVote(1L, "voter@example.com")).thenReturn(testVote);

        mockMvc.perform(post("/api/requests/{requestId}/vote", 1L)
                        .param("voter", "voter@example.com"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId").value(1))
                .andExpect(jsonPath("$.voter").value("voter@example.com"));

        verify(applicationService).submitVote(1L, "voter@example.com");
    }

    @Test
    void submitVoteShouldReturnBadRequestWhenAlreadyVoted() throws Exception {
        when(applicationService.submitVote(1L, "voter@example.com"))
                .thenThrow(new IllegalArgumentException("User has already voted"));

        mockMvc.perform(post("/api/requests/{requestId}/vote", 1L)
                        .param("voter", "voter@example.com"))
                .andExpect(status().isBadRequest());

        verify(applicationService).submitVote(1L, "voter@example.com");
    }

    // ===== GET /api/requests/{requestId}/votes/count =====

    @Test
    void getVoteCountShouldReturnCount() throws Exception {
        when(applicationService.getVoteCount(1L)).thenReturn(5L);

        mockMvc.perform(get("/api/requests/{requestId}/votes/count", 1L))
                .andExpect(status().isOk())
                .andExpect(content().string("5"));

        verify(applicationService).getVoteCount(1L);
    }

    // ===== GET /api/requests/{requestId}/votes/check =====

    @Test
    void hasUserVotedShouldReturnTrueWhenVoted() throws Exception {
        when(applicationService.hasUserVoted(1L, "voter@example.com")).thenReturn(true);

        mockMvc.perform(get("/api/requests/{requestId}/votes/check", 1L)
                        .param("voter", "voter@example.com"))
                .andExpect(status().isOk())
                .andExpect(content().string("true"));

        verify(applicationService).hasUserVoted(1L, "voter@example.com");
    }

    @Test
    void hasUserVotedShouldReturnFalseWhenNotVoted() throws Exception {
        when(applicationService.hasUserVoted(1L, "voter@example.com")).thenReturn(false);

        mockMvc.perform(get("/api/requests/{requestId}/votes/check", 1L)
                        .param("voter", "voter@example.com"))
                .andExpect(status().isOk())
                .andExpect(content().string("false"));

        verify(applicationService).hasUserVoted(1L, "voter@example.com");
    }

    // ===== GET /api/categories/counts =====

    @Test
    void getCategoryCountsShouldReturnCounts() throws Exception {
        List<CategoryCountDto> counts = Arrays.asList(
                new CategoryCountDto("LDAP", 5L),
                new CategoryCountDto("HR Systems", 3L)
        );
        when(applicationService.getCategoryCounts()).thenReturn(counts);

        mockMvc.perform(get("/api/categories/counts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].displayName").value("LDAP"))
                .andExpect(jsonPath("$[0].count").value(5));

        verify(applicationService).getCategoryCounts();
    }

    // ===== GET /api/applications =====

    @Test
    void getAllApplicationsShouldReturnList() throws Exception {
        ApplicationCardDto dto = new ApplicationCardDto(
                testAppId,
                "Test Application",
                "Test Description",
                null,
                "ACTIVE",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        List<ApplicationCardDto> applications = Collections.singletonList(dto);
        Page<ApplicationCardDto> page = new PageImpl<>(applications);
        when(applicationService.list(any(), eq(null), eq(null))).thenReturn(page);

        mockMvc.perform(get("/api/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(testAppId.toString()))
                .andExpect(jsonPath("$[0].displayName").value("Test Application"));

        verify(applicationService).list(any(), eq(null), eq(null));
    }

    @Test
    void getAllApplicationsShouldReturnEmptyListWhenNoApplications() throws Exception {
        Page<ApplicationCardDto> emptyPage = new PageImpl<>(Collections.emptyList());
        when(applicationService.list(any(), eq(null), eq(null))).thenReturn(emptyPage);

        mockMvc.perform(get("/api/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(applicationService).list(any(), eq(null), eq(null));
    }

    // TODO, Set up positive scenario
    @Disabled("TODO: Set up positive scenario")
    @Test
    void verifyConnectorBundleVersionNoBundleWithSuchClassName() throws Exception {
        VerifyBundleInformationForm verifyBundleInformationForm = new VerifyBundleInformationForm();
        verifyBundleInformationForm.setOid(testVersionId);
        verifyBundleInformationForm.setClassName("com.evolveum.polygon.connector.test.TestFooConnector");
        verifyBundleInformationForm.setVersion("1.0.0");

        when(applicationService.verify(any(VerifyBundleInformationForm.class)))
                .thenReturn(true);

        mockMvc.perform(post("/upload/verify/{bundleName}", "test-bundle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyBundleInformationForm)))
                .andExpect(status().isOk());

        verify(applicationService).verify(any(VerifyBundleInformationForm.class));
    }

    // TODO, Set up conflict scenario
    @Disabled("TODO: Set up conflict scenario")
    @Test
    void verifyConnectorBundleVersionBundleWithSuchClassName() throws Exception {
        VerifyBundleInformationForm verifyBundleInformationForm = new VerifyBundleInformationForm();
        verifyBundleInformationForm.setOid(testVersionId);
        verifyBundleInformationForm.setClassName("com.evolveum.polygon.connector.test.TestFooConnector");
        verifyBundleInformationForm.setVersion("1.0.0");

        when(applicationService.verify(any(VerifyBundleInformationForm.class)))
                .thenReturn(false);

        mockMvc.perform(post("/upload/verify/{bundleName}", "test-bundle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyBundleInformationForm)))
                .andExpect(status().isConflict());

        verify(applicationService).verify(any(VerifyBundleInformationForm.class));
    }

    // TODO, Set up Not found
    @Disabled("TODO: Set up Not found scenario")
    @Test
    void verifyConnectorBundleVersionNoSuchBundle() throws Exception {
        VerifyBundleInformationForm verifyBundleInformationForm = new VerifyBundleInformationForm();
        verifyBundleInformationForm.setOid(testVersionId);
        verifyBundleInformationForm.setClassName("com.evolveum.polygon.connector.test.TestFooConnector");
        verifyBundleInformationForm.setVersion("1.0.0");

        when(applicationService.verify(any(VerifyBundleInformationForm.class)))
                .thenReturn(false);

        mockMvc.perform(post("/upload/verify/{bundleName}", "test-bundle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyBundleInformationForm)))
                .andExpect(status().isNotFound());

        verify(applicationService).verify(any(VerifyBundleInformationForm.class));
    }

    // ===== GET /api/connectors/catalog =====

    @Test
    void getCatalogConnectorsShouldReturnList() throws Exception {
        CatalogConnectorDto dto = new CatalogConnectorDto(
                1,
                "LDAP Connector",
                "LDAP connector for directory services",
                "1.0.0",
                "Polygon LDAP",
                "Evolveum",
                "APACHE_2",
                "MAVEN",
                "JAVA_BASED",
                "https://github.com/Evolveum/connector-ldap",
                "https://github.com/Evolveum/connector-ldap.git",
                null,
                "com.evolveum.polygon.connector.ldap.LdapConnector",
                List.of()
        );
        when(applicationService.listCatalogConnectors()).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/connectors/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].displayName").value("LDAP Connector"))
                .andExpect(jsonPath("$[0].bundleFramework").value("JAVA_BASED"));

        verify(applicationService).listCatalogConnectors();
    }

    @Test
    void getCatalogConnectorsShouldReturnEmptyListWhenNoneActive() throws Exception {
        when(applicationService.listCatalogConnectors()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/connectors/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(applicationService).listCatalogConnectors();
    }

    // ===== POST /api/upload/connector =====

    @Test
    void uploadConnectorShouldReturnOkWhenSuccessful() throws Exception {
        when(applicationService.uploadConnector(any(UploadImplementationDto.class), anyString()))
                .thenReturn("app-uuid|method-uuid");

        mockMvc.perform(post("/api/upload/connector")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        verify(applicationService).uploadConnector(any(UploadImplementationDto.class), anyString());
    }

    @Test
    void uploadConnectorShouldReturnBadRequestOnIllegalArgument() throws Exception {
        when(applicationService.uploadConnector(any(UploadImplementationDto.class), anyString()))
                .thenThrow(new IllegalArgumentException("Framework must be specified"));

        mockMvc.perform(post("/api/upload/connector")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(applicationService).uploadConnector(any(UploadImplementationDto.class), anyString());
    }

    @Test
    void uploadConnectorShouldReturnConflictOnDuplicateBundle() throws Exception {
        when(applicationService.uploadConnector(any(UploadImplementationDto.class), anyString()))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        mockMvc.perform(post("/api/upload/connector")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());

        verify(applicationService).uploadConnector(any(UploadImplementationDto.class), anyString());
    }

    // ===== GET /api/connectors/active =====

//    @Test
//    void getActiveConnectorsShouldReturnList() throws Exception {
//        ActiveConnectorDto dto = new ActiveConnectorDto(
//                testAppId,
//                "Test Application",
//                "Test Description",
//                null,
//                null,
//                null,
//                null,
//                null,
//                null
//        );
//
//        List<ActiveConnectorDto> connectors = Collections.singletonList(dto);
//        when(applicationService.listActiveConnectors()).thenReturn(connectors);
//
//        mockMvc.perform(get("/api/connectors/active"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.length()").value(1))
//                .andExpect(jsonPath("$[0].id").value(testAppId.toString()))
//                .andExpect(jsonPath("$[0].displayName").value("Test Application"));
//
//        verify(applicationService).listActiveConnectors();
//    }
//
//    @Test
//    void getActiveConnectorsShouldReturnEmptyListWhenNoActiveConnectors() throws Exception {
//        when(applicationService.listActiveConnectors()).thenReturn(Collections.emptyList());
//
//        mockMvc.perform(get("/api/connectors/active"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.length()").value(0));
//
//        verify(applicationService).listActiveConnectors();
//    }
}
