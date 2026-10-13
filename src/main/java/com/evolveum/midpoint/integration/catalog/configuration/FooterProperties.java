/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Content of the page footer, kept in configuration so a link or a whole column changes without a
 * code change. The layout fits {@value #MAX_COLUMNS} columns of {@value #MAX_ITEMS} items; anything
 * beyond that is dropped with a warning at startup.
 */
@Slf4j
@ConfigurationProperties(prefix = "catalog.footer")
public record FooterProperties(
        List<Column> columns,
        Social social,
        List<Link> bottomLinks
) {

    public static final int MAX_COLUMNS = 4;
    public static final int MAX_ITEMS = 4;

    public FooterProperties {
        columns = columns == null ? List.of() : columns;
        if (columns.size() > MAX_COLUMNS) {
            log.warn("Footer has {} columns, only the first {} are shown", columns.size(), MAX_COLUMNS);
            columns = columns.subList(0, MAX_COLUMNS);
        }
        columns = columns.stream().map(Column::limited).toList();
        social = social == null ? new Social(null, null, null, null, null) : social;
        bottomLinks = bottomLinks == null ? List.of() : List.copyOf(bottomLinks);
    }

    public record Column(String label, List<Link> items) {

        private Column limited() {
            List<Link> all = items == null ? List.of() : items;
            if (all.size() <= MAX_ITEMS) {
                return new Column(label, List.copyOf(all));
            }
            log.warn("Footer column '{}' has {} items, only the first {} are shown", label, all.size(), MAX_ITEMS);
            return new Column(label, List.copyOf(all.subList(0, MAX_ITEMS)));
        }
    }

    /**
     * @param link absolute URL (opens in a new tab) or an in-app path such as {@code ./support-tiers}
     */
    public record Link(String label, String link) {
    }

    /** Profile URLs; the frontend shows them in a fixed order and hides the empty ones. */
    public record Social(String linkedin, String youtube, String bluesky, String mastodon, String github) {
    }
}
