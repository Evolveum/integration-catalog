package com.evolveum.midpoint.integration.catalog.dto;

import com.evolveum.midpoint.integration.catalog.object.Request;

public record RequestDto(
        Long id,
        String requester,
        String applicationName,
        String capabilitiesType,
        long votesCount
) {
    public static RequestDto fromEntity(Request request) {
        return new RequestDto(
                request.getId(),
                request.getRequester(),
                request.getApplicationId() != null ? request.getApplicationId().toString() : null,
                request.getCapabilitiesType() != null ? request.getCapabilitiesType().name() : null,
                request.getVotesCount()
        );
    }
}
