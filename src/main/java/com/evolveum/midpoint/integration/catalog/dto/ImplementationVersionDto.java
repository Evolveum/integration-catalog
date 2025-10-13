/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.util.List;

import java.time.LocalDate;

public class ImplementationVersionDto {
    private String description;
    private List<String> implementationTags;
    private List<String> capabilities;
    private String connectorVersion;
    private String systemVersion;
    private LocalDate releasedDate;
    private String author;
    private String lifecycleState;

    public ImplementationVersionDto(String description, List<String> implementationTags, List<String> capabilities, String connectorVersion, String systemVersion, LocalDate releasedDate, String author, String lifecycleState) {
        this.description = description;
        this.implementationTags = implementationTags;
        this.capabilities = capabilities;
        this.connectorVersion = connectorVersion;
        this.systemVersion = systemVersion;
        this.releasedDate = releasedDate;
        this.author = author;
        this.lifecycleState = lifecycleState;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getImplementationTags() {
        return implementationTags;
    }

    public List<String> getCapabilities() {
        return capabilities;
    }

    public String getConnectorVersion() {
        return connectorVersion;
    }

    public String getSystemVersion() {
        return systemVersion;
    }

    public LocalDate getReleasedDate() {
        return releasedDate;
    }

    public String getAuthor() {
        return author;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }
}
