/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.object.Connector;
import com.evolveum.midpoint.integration.catalog.object.ConnectorConnectorTag;
import com.evolveum.midpoint.integration.catalog.object.ConnectorTag;
import com.evolveum.midpoint.integration.catalog.object.SupportTier;
import com.evolveum.midpoint.integration.catalog.repository.ConnectorRepository;
import com.evolveum.midpoint.integration.catalog.repository.ConnectorTagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * The superuser-set tags of a connector. Support tier and obsolete are independent: a connector with
 * a modern alternative can stay supported while users migrate to it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConnectorTagsTest {

    private static final int ID = 7;

    @Mock
    private ConnectorRepository connectorRepository;

    @Mock
    private ConnectorTagRepository connectorTagRepository;

    @InjectMocks
    private ApplicationService applicationService;

    private final Connector connector = new Connector();

    @BeforeEach
    void setUp() {
        connector.setConnectorConnectorTags(new HashSet<>());
        when(connectorRepository.findById(ID)).thenReturn(Optional.of(connector));
        when(connectorRepository.findByClonedFrom(ID)).thenReturn(List.of());
        for (String name : List.of("supp_tier_standard", "supp_tier_premium", "obsolete", "ai_generated")) {
            when(connectorTagRepository.findByName(name)).thenReturn(Optional.of(tag(name)));
        }
    }

    private static ConnectorTag tag(String name) {
        ConnectorTag tag = new ConnectorTag();
        tag.setName(name);
        return tag;
    }

    private void connectorHas(String name) {
        ConnectorConnectorTag link = new ConnectorConnectorTag();
        link.setConnector(connector);
        link.setConnectorTag(tag(name));
        connector.getConnectorConnectorTags().add(link);
    }

    private Set<String> tags() {
        return connector.getConnectorConnectorTags().stream()
                .map(link -> link.getConnectorTag().getName())
                .collect(Collectors.toSet());
    }

    @Test
    void aConnectorCanBeObsoleteAndKeepTheHighestTier() {
        applicationService.setConnectorTags(ID, SupportTier.PREMIUM, true);

        assertEquals(Set.of("supp_tier_premium", "obsolete"), tags());
        assertEquals(SupportTier.PREMIUM, connector.supportTier());
    }

    @Test
    void markingATieredConnectorObsoleteKeepsItsTier() {
        connectorHas("supp_tier_standard");

        applicationService.setConnectorTags(ID, SupportTier.STANDARD, true);

        assertEquals(Set.of("supp_tier_standard", "obsolete"), tags());
    }

    @Test
    void clearingObsoleteKeepsTheTierAndClearingTheTierKeepsObsolete() {
        applicationService.setConnectorTags(ID, SupportTier.PREMIUM, true);

        applicationService.setConnectorTags(ID, SupportTier.PREMIUM, false);
        assertEquals(Set.of("supp_tier_premium"), tags());

        applicationService.setConnectorTags(ID, SupportTier.PREMIUM, true);
        applicationService.setConnectorTags(ID, null, true);
        assertEquals(Set.of("obsolete"), tags());
    }

    @Test
    void otherTagsAreLeftAlone() {
        connectorHas("ai_generated");

        applicationService.setConnectorTags(ID, SupportTier.PREMIUM, true);

        assertEquals(Set.of("ai_generated", "supp_tier_premium", "obsolete"), tags());
    }
}
