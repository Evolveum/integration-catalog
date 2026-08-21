/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for how {@link OpenProjectClient} reads a refused upload.
 *
 * <p>The distinction under test is the one the retry mechanism turns on: a file the portal will
 * never take has to be recognised as such, or it is offered again on every scheduled run for as
 * long as the revision exists; and a failure that is merely this minute's has to stay retryable, or
 * a submission loses a file to a portal that was only briefly unwell.
 */
class OpenProjectClientTest {

    @Test
    void readsAProxyCuttingTheUploadOffAsTooLarge() {
        // Nothing of the portal's own reaches the catalog here - the proxy in front of it answers.
        assertThat(OpenProjectClient.isTooLarge(413, "<html>413 Request Entity Too Large</html>")).isTrue();
    }

    @Test
    void readsThePortalsOwnSizeComplaintAsTooLarge() {
        String body = "{\"_type\":\"Error\",\"errorIdentifier\":"
                + "\"urn:openproject-org:api:v3:errors:PropertyConstraintViolation\","
                + "\"message\":\"File is too large (maximum size is 5242880 Bytes).\"}";

        assertThat(OpenProjectClient.isTooLarge(422, body)).isTrue();
    }

    @Test
    void doesNotReadOtherConstraintViolationsAsTooLarge() {
        String body = "{\"_type\":\"Error\",\"errorIdentifier\":"
                + "\"urn:openproject-org:api:v3:errors:PropertyConstraintViolation\","
                + "\"message\":\"File name can't be blank.\"}";

        assertThat(OpenProjectClient.isTooLarge(422, body)).isFalse();
    }

    @Test
    void treatsAPortalThatIsSimplyBrokenAsWorthRetrying() {
        assertThat(OpenProjectClient.isTooLarge(500, "Internal Server Error")).isFalse();
        assertThat(OpenProjectClient.isTooLarge(502, "Bad Gateway")).isFalse();
        assertThat(OpenProjectClient.isTooLarge(422, null)).isFalse();
    }
}
