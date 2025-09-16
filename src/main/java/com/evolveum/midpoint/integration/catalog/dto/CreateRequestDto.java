package com.evolveum.midpoint.integration.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateRequestDto(
        @NotNull Long applicationId,
        @NotNull String capabilitiesType, // READ|CREATE|MODIFY|DELETE
        @NotBlank String requester
) {}
