/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import lombok.Getter;

import java.util.List;

import java.time.LocalDate;

@Getter
public class ImplementationVersionDto {
    private String description;
    private List<String> implementationTags;
    private List<String> capabilities;
    private String connectorVersion;
    private String systemVersion;
    private LocalDate releasedDate;
    private String author;
    private String lifecycleState;
    private String downloadLink;

    public ImplementationVersionDto(String description, List<String> implementationTags, List<String> capabilities, String connectorVersion, String systemVersion, LocalDate releasedDate, String author, String lifecycleState, String downloadLink) {
        this.description = description;
        this.implementationTags = implementationTags;
        this.capabilities = capabilities;
        this.connectorVersion = connectorVersion;
        this.systemVersion = systemVersion;
        this.releasedDate = releasedDate;
        this.author = author;
        this.lifecycleState = lifecycleState;
        this.downloadLink = downloadLink;
    }

}
