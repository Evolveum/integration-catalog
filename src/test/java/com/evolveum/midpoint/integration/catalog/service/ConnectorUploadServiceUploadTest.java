/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.configuration.GithubProperties;
import com.evolveum.midpoint.integration.catalog.configuration.JenkinsProperties;
import com.evolveum.midpoint.integration.catalog.dto.MaintainerDto;
import com.evolveum.midpoint.integration.catalog.dto.UploadConnectorDto;
import com.evolveum.midpoint.integration.catalog.dto.UploadIntegrationDto;
import com.evolveum.midpoint.integration.catalog.dto.UploadIntegrationMethodDto;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.evolveum.midpoint.integration.catalog.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Covers publishing a method together with a brand-new connector: every row the upload creates must
 * be stamped with its author and maintainer, since the connector, bundle, bundle version and connector
 * version tables all declare those columns NOT NULL.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConnectorUploadServiceUploadTest {

    private static final String USERNAME = "u3";
    private static final MaintainerDto MAINTAINER =
            new MaintainerDto(null, USERNAME, null, MaintainerType.USER, USERNAME);

    @Mock private ApplicationRepository applicationRepository;
    @Mock private IntegrationMethodRepository integrationMethodRepository;
    @Mock private ConnectorRepository connectorRepository;
    @Mock private ConnectorVersionRepository connectorVersionRepository;
    @Mock private ConnectorBundleRepository connectorBundleRepository;
    @Mock private ConnectorBundleVersionRepository connectorBundleVersionRepository;
    @Mock private DownloadRepository downloadRepository;
    @Mock private ApplicationTagService applicationTagService;
    @Mock private CapabilityRepository capabilityRepository;
    @Mock private IntegrationMethodCapabilityRepository integrationMethodCapabilityRepository;
    @Mock private IntegrationMethodCapabilityItemRepository integrationMethodCapabilityItemRepository;
    @Mock private ConnVersionCapabilityRepository connVersionCapabilityRepository;
    @Mock private ConnVersionCapabilityItemRepository connVersionCapabilityItemRepository;
    @Mock private IntegrationMethodTypeRepository integrationMethodTypeRepository;
    @Mock private IntegrationMethodConnectorRepository integrationMethodConnectorRepository;
    @Mock private TutorialStorageService tutorialStorageService;
    @Mock private OwnershipService ownershipService;
    @Mock private ApplicationEventPublisher events;

    private ConnectorUploadService service;

    @BeforeEach
    void setUp() {
        service = new ConnectorUploadService(
                applicationRepository, integrationMethodRepository, connectorRepository,
                connectorVersionRepository, connectorBundleRepository, connectorBundleVersionRepository,
                downloadRepository,
                new GithubProperties("token", "group", "template"),
                new JenkinsProperties("http://jenkins", "token", "user", "job", "callback-token"),
                applicationTagService, capabilityRepository, integrationMethodCapabilityRepository,
                integrationMethodCapabilityItemRepository, connVersionCapabilityRepository,
                connVersionCapabilityItemRepository, integrationMethodTypeRepository,
                integrationMethodConnectorRepository, tutorialStorageService, ownershipService, events);
    }

    @Test
    void newConnectorIsStampedWithAuthorAndMaintainer() {
        service.uploadIntegration(uploadWithNewConnector(), USERNAME);

        ArgumentCaptor<Connector> saved = ArgumentCaptor.forClass(Connector.class);
        verify(connectorRepository).save(saved.capture());
        Connector connector = saved.getValue();
        assertEquals("PeopleSoft HCM connector", connector.getDisplayName());

        // The very instance that is saved must have been stamped - stamping any other row in its
        // place leaves connector.author and connector.maintainer NULL.
        verify(ownershipService).stampNew(same(connector), eq(USERNAME), eq(MAINTAINER));
    }

    @Test
    void everyNewRowIsStampedOnce() {
        service.uploadIntegration(uploadWithNewConnector(), USERNAME);

        ArgumentCaptor<SetOwnership> stamped = ArgumentCaptor.forClass(SetOwnership.class);
        verify(ownershipService, times(5)).stampNew(stamped.capture(), eq(USERNAME), eq(MAINTAINER));

        List<Class<?>> kinds = stamped.getAllValues().stream().<Class<?>>map(Object::getClass).toList();
        for (Class<?> kind : List.of(IntegrationMethod.class, Connector.class, ConnectorBundle.class,
                ConnectorBundleVersion.class, ConnectorVersion.class)) {
            assertEquals(1, kinds.stream().filter(kind::equals).count(), kind.getSimpleName() + " stamps");
        }
        verify(connectorBundleRepository).save(any(ConnectorBundle.class));
    }

    private static UploadIntegrationDto uploadWithNewConnector() {
        Application application = new Application();
        application.setDisplayName("PeopleSoft");

        UploadIntegrationMethodDto method = new UploadIntegrationMethodDto(
                null, "PeopleSoft via HCM connector", "1.0", "Method description", null,
                null, MAINTAINER, List.of(), 5, 9);

        UploadConnectorDto connector = new UploadConnectorDto(
                "PeopleSoft HCM connector", ConnectorBundle.FrameworkType.JAVA_BASED, "1.0.0",
                ConnectorBundle.LicenseType.APACHE_2, BuildFrameworkType.MAVEN, "See integration method",
                MAINTAINER, "https://example.org/hcm", "https://example.org/hcm/issues",
                "https://example.org/hcm.git", "com.evolveum.polygon.connector.hcm.HcmConnector",
                null, "v1.0.0", "HCM bundle", null, "1.0.0", null);

        return new UploadIntegrationDto(application, method, connector, List.of(), List.of(), List.of());
    }
}
