/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.configuration.GithubProperties;
import com.evolveum.midpoint.integration.catalog.configuration.JenkinsProperties;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.evolveum.midpoint.integration.catalog.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers what approving a revision does to the connector graph: folding a copy-on-write clone back into
 * the connector it came from, and retiring the original's bundle when the clone carries a new version.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConnectorUploadServiceApproveTest {

    private static final UUID METHOD_ID = UUID.randomUUID();
    private static final String METHOD_REVISION = "1.0";
    private static final String BUNDLE_NAME = "com.evolveum.polygon.connector.ldap";
    private static final String REVIEWER = "reviewer";

    private static final int ORIGINAL_BUNDLE_ID = 41;
    private static final int ORIGINAL_CONNECTOR_ID = 41;
    private static final int SIBLING_CONNECTOR_ID = 42;
    private static final int OLD_VERSION_ID = 77;
    private static final int CLONE_BUNDLE_ID = 90;
    private static final int CLONE_CONNECTOR_ID = 58;
    private static final String ROW_REVISION = "1.0";

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
    @Mock private ApplicationEventPublisher events;

    private ConnectorUploadService service;

    /** The connectors the mocked repository can find by id, so {@code reload} returns the fixture rows. */
    private final Map<Integer, Connector> connectorsById = new HashMap<>();

    private IntegrationMethod draft;
    private IntegrationMethodConnector draftLink;
    private IntegrationMethodConnector otherMethodLink;

    private ConnectorBundle originalBundle;
    private ConnectorBundleVersion original100;
    private Connector original;
    private ConnectorVersion originalCv;
    private Connector sibling;
    private ConnectorVersion siblingCv;

    private ConnectorBundle cloneBundle;
    private ConnectorBundleVersion clone100;
    private ConnectorBundleVersion clone110;
    private Connector clone;
    private ConnectorVersion cloneCv110;

    private Download download;

    @BeforeEach
    void setUp() {
        service = new ConnectorUploadService(
                applicationRepository, integrationMethodRepository, connectorRepository,
                connectorVersionRepository, connectorBundleRepository, connectorBundleVersionRepository,
                downloadRepository,
                new GithubProperties("token", "group", "template"),
                new JenkinsProperties("http://jenkins", "token", "user", "job"),
                applicationTagService, capabilityRepository, integrationMethodCapabilityRepository,
                integrationMethodCapabilityItemRepository, connVersionCapabilityRepository,
                connVersionCapabilityItemRepository, integrationMethodTypeRepository,
                integrationMethodConnectorRepository, tutorialStorageService, events);

        buildPublishedBundle();
        buildDraftClone();
        buildMethod();
        stubRepositories();
    }

    /**
     * The published side: one ACTIVE bundle at 1.0.0 holding two connectors — the shape a Maven artifact
     * shipping two connector classes has after a build reports both.
     */
    private void buildPublishedBundle() {
        originalBundle = new ConnectorBundle();
        originalBundle.setId(ORIGINAL_BUNDLE_ID);
        originalBundle.setRevision(ROW_REVISION);
        originalBundle.setBundleName(BUNDLE_NAME);
        originalBundle.setDisplayName("LDAP Connector");
        originalBundle.setLifecycleState(LifecycleType.ACTIVE);
        originalBundle.setFramework(ConnectorBundle.FrameworkType.JAVA_BASED);
        originalBundle.setLicense(ConnectorBundle.LicenseType.APACHE_2);
        originalBundle.setProjectHomepage("https://example.org/ldap-old");

        original100 = bundleVersion(OLD_VERSION_ID, "1.0.0", originalBundle, LifecycleType.ACTIVE);

        original = connector(ORIGINAL_CONNECTOR_ID, "LdapConnector", originalBundle);
        originalCv = connectorVersion(101, original, original100, LifecycleType.ACTIVE);

        sibling = connector(SIBLING_CONNECTOR_ID, "AdConnector", originalBundle);
        siblingCv = connectorVersion(102, sibling, original100, LifecycleType.ACTIVE);

        download = new Download();
        download.setId(7);
        download.setConnectorBundleVersion(original100);
    }

    /**
     * The draft side: what {@code updateConnector} leaves behind when a connector on two links is edited
     * into a new version — a clone in its own IN_REVIEW bundle carrying the same name and revision.
     */
    private void buildDraftClone() {
        cloneBundle = new ConnectorBundle();
        cloneBundle.setId(CLONE_BUNDLE_ID);
        cloneBundle.setRevision(ROW_REVISION);
        cloneBundle.setBundleName(BUNDLE_NAME);
        cloneBundle.setDisplayName("LDAP Connector");
        cloneBundle.setLifecycleState(LifecycleType.IN_REVIEW);
        cloneBundle.setFramework(ConnectorBundle.FrameworkType.JAVA_BASED);
        cloneBundle.setLicense(ConnectorBundle.LicenseType.APACHE_2);
        cloneBundle.setProjectHomepage("https://example.org/ldap-old");

        clone100 = bundleVersion(95, "1.0.0", cloneBundle, LifecycleType.IN_REVIEW);
        clone110 = bundleVersion(96, "1.1.0", cloneBundle, LifecycleType.IN_REVIEW);

        clone = connector(CLONE_CONNECTOR_ID, "LdapConnector", cloneBundle);
        clone.setClonedFrom(ORIGINAL_CONNECTOR_ID);
        connectorVersion(201, clone, clone100, LifecycleType.IN_REVIEW);
        cloneCv110 = connectorVersion(202, clone, clone110, LifecycleType.IN_REVIEW);
    }

    /** The revision under review, already pointing at the clone, plus a second method on the original. */
    private void buildMethod() {
        Application application = new Application();
        application.setId(UUID.randomUUID());
        application.setLifecycleState(Application.ApplicationLifecycleType.ACTIVE);

        draft = new IntegrationMethod();
        draft.setId(METHOD_ID);
        draft.setRevision(METHOD_REVISION);
        draft.setLifecycleState(LifecycleType.IN_REVIEW);
        draft.setApplication(application);

        draftLink = new IntegrationMethodConnector();
        draftLink.setId(1);
        draftLink.setIntegrationMethod(draft);
        draftLink.setConnector(clone);
        draftLink.setConnectorMinVersion("1.0.0");
        draft.getConnectors().add(draftLink);

        // A second method still linking the published connector — the reason it was cloned at all.
        otherMethodLink = new IntegrationMethodConnector();
        otherMethodLink.setId(2);
        otherMethodLink.setConnector(original);
        otherMethodLink.setConnectorMinVersion("1.0.0");
    }

    private void stubRepositories() {
        when(integrationMethodRepository.findById(any())).thenReturn(Optional.of(draft));
        when(integrationMethodRepository.findByApplicationId(any())).thenReturn(List.of(draft));
        when(connectorRepository.findById(anyInt()))
                .thenAnswer(call -> Optional.ofNullable(connectorsById.get(call.<Integer>getArgument(0))));
        when(connectorRepository.findByClonedFrom(anyInt())).thenReturn(List.of());
        when(integrationMethodConnectorRepository.findByConnector_Id(ORIGINAL_CONNECTOR_ID))
                .thenReturn(new ArrayList<>(List.of(otherMethodLink)));
        when(downloadRepository.findByConnectorBundleVersion(original100)).thenReturn(List.of(download));
        when(connectorRepository.save(any())).thenAnswer(returnsFirstArg());
        when(connectorVersionRepository.save(any())).thenAnswer(returnsFirstArg());
        when(connectorBundleVersionRepository.save(any())).thenAnswer(returnsFirstArg());
    }

    // ── the multi-connector bundle: the case that used to break ──────────────

    /**
     * The old bundle still holds a sibling connector, so it may not simply be left standing: two ACTIVE
     * rows would share one bundle_name. It is emptied into the clone's bundle and its row dropped.
     */
    @Test
    void versionBumpOnSharedBundleMovesSiblingsAcrossAndDropsTheOldBundleRow() {
        service.publishIntegrationMethod(METHOD_ID, METHOD_REVISION, REVIEWER);

        // The sibling's versions join the copy of 1.0.0 the clone already carries, and the old row goes.
        verify(connectorVersionRepository).moveAllToBundleVersion(original100, clone100);
        verify(connectorBundleVersionRepository).deleteRow(OLD_VERSION_ID, ROW_REVISION);
        // Anything the clone did not copy comes along as it is, then the connectors themselves.
        verify(connectorBundleVersionRepository).moveAllToBundle(originalBundle, cloneBundle);
        verify(connectorRepository).moveAllToBundle(originalBundle, cloneBundle);
        verify(connectorBundleRepository).deleteRow(ORIGINAL_BUNDLE_ID);

        // The regression guard: an entity delete here cascades REMOVE into the connectors just moved out.
        verify(connectorBundleRepository, never()).delete(any(ConnectorBundle.class));

        assertNull(clone.getClonedFrom(), "the clone is the connector now, not a copy of one");
        assertSame(clone, otherMethodLink.getConnector(), "the other method follows the survivor");
        assertEquals(LifecycleType.ACTIVE, cloneBundle.getLifecycleState());
        assertEquals(LifecycleType.ACTIVE, clone110.getLifecycleState());
        assertEquals(LifecycleType.ACTIVE, cloneCv110.getLifecycleState());
        assertEquals(LifecycleType.ACTIVE, draft.getLifecycleState());
    }

    /** Downloads cascade with their bundle version, so they have to be repointed before it is deleted. */
    @Test
    void downloadHistoryMovesBeforeTheOldVersionRowIsDeleted() {
        service.publishIntegrationMethod(METHOD_ID, METHOD_REVISION, REVIEWER);

        InOrder order = inOrder(downloadRepository, connectorBundleVersionRepository);
        order.verify(downloadRepository).save(download);
        order.verify(connectorBundleVersionRepository).deleteRow(OLD_VERSION_ID, ROW_REVISION);
        assertSame(clone100, download.getConnectorBundleVersion());
    }

    /** The ordinary case — one connector in the bundle — ends where it always did. */
    @Test
    void versionBumpOnASoleConnectorRetiresTheBundleTheSameWay() {
        originalBundle.getConnectors().remove(sibling);
        original100.getConnectorVersions().remove(siblingCv);
        connectorsById.remove(SIBLING_CONNECTOR_ID);

        service.publishIntegrationMethod(METHOD_ID, METHOD_REVISION, REVIEWER);

        verify(connectorBundleRepository).deleteRow(ORIGINAL_BUNDLE_ID);
        verify(connectorBundleRepository, never()).delete(any(ConnectorBundle.class));
        verify(connectorRepository).moveAllToBundle(originalBundle, cloneBundle);
    }

    // ── the other two approve outcomes, so the branches stay separated ───────

    /**
     * An edit that did not change the version is a correction of it: the values are written onto the
     * original — where every method linking it sees them — and the clone graph is dropped.
     */
    @Test
    void sameVersionEditFoldsBackIntoTheOriginalAndLeavesBothBundlesInPlace() {
        clone.getConnectorVersions().remove(cloneCv110);
        cloneBundle.getBundleVersions().remove(clone110);
        clone.setDisplayName("LDAP Connector (fixed)");
        cloneBundle.setProjectHomepage("https://example.org/ldap");

        service.publishIntegrationMethod(METHOD_ID, METHOD_REVISION, REVIEWER);

        assertEquals("LDAP Connector (fixed)", original.getDisplayName());
        assertEquals("https://example.org/ldap", originalBundle.getProjectHomepage());
        assertSame(original, draftLink.getConnector(), "the method goes back to the shared connector");
        verify(connectorRepository).delete(clone);
        verify(connectorBundleRepository).delete(cloneBundle);
        verify(connectorRepository, never()).moveAllToBundle(any(), any());
        verify(connectorBundleRepository, never()).deleteRow(anyInt());
    }

    /** A clone that ended up under a different bundle name is a different connector; nothing is retired. */
    @Test
    void cloneInAnotherBundleStaysItsOwnConnector() {
        cloneBundle.setBundleName(BUNDLE_NAME + ".v2");

        service.publishIntegrationMethod(METHOD_ID, METHOD_REVISION, REVIEWER);

        verify(connectorBundleRepository, never()).deleteRow(anyInt());
        verify(connectorRepository, never()).delete(any(Connector.class));
        verify(connectorRepository, never()).moveAllToBundle(any(), any());
        assertSame(clone, draftLink.getConnector());
        assertEquals(LifecycleType.ACTIVE, cloneBundle.getLifecycleState());
        assertEquals(ORIGINAL_CONNECTOR_ID, clone.getClonedFrom(),
                "still a clone of the original, which keeps its own methods");
    }

    // ── fixture helpers ─────────────────────────────────────────────────────

    private Connector connector(int id, String className, ConnectorBundle bundle) {
        Connector connector = new Connector();
        connector.setId(id);
        connector.setRevision("1.0.0");
        connector.setDisplayName(className);
        connector.setFullyQualifiedClassName("com.evolveum.polygon.connector." + className);
        connector.setMaintainer("owner");
        connector.setConnectorBundle(bundle);
        bundle.getConnectors().add(connector);
        connectorsById.put(id, connector);
        return connector;
    }

    private ConnectorBundleVersion bundleVersion(int id, String version, ConnectorBundle bundle,
                                                 LifecycleType state) {
        ConnectorBundleVersion cbv = new ConnectorBundleVersion();
        cbv.setId(id);
        cbv.setRevision(ROW_REVISION);
        cbv.setBundleVersion(version);
        cbv.setLifecycleState(state);
        cbv.setConnectorBundle(bundle);
        bundle.getBundleVersions().add(cbv);
        return cbv;
    }

    private ConnectorVersion connectorVersion(int id, Connector connector, ConnectorBundleVersion cbv,
                                              LifecycleType state) {
        ConnectorVersion cv = new ConnectorVersion();
        cv.setId(id);
        cv.setRevision(cbv.getBundleVersion());
        cv.setLifecycleState(state);
        cv.setConnector(connector);
        cv.setConnectorBundleVersion(cbv);
        connector.getConnectorVersions().add(cv);
        cbv.getConnectorVersions().add(cv);
        return cv;
    }
}
