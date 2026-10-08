/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** The edit comment names only the capabilities whose state changed, not every capability of the object. */
class SupportTicketDeltaBuilderCapabilityTest {

    private final SupportTicketDeltaBuilder builder = new SupportTicketDeltaBuilder();

    private static String description(String read, String create, String delete) {
        return """
                ### Integration method capabilities

                * **Object `Account`:**
                    * **Read:** %s
                    * **Create:** %s
                    * **Delete:** %s
                """.formatted(read, create, delete);
    }

    @Test
    void onlyTheChangedCapabilityIsListed() {
        Optional<String> comment = builder.compare(
                description("Yes", "No", "Unknown"),
                description("Yes", "Yes", "Unknown"),
                "Edited.", List.of());

        assertThat(comment).hasValueSatisfying(text -> {
            assertThat(text).contains("* **Object `Account` / Create:** No → Yes");
            assertThat(text).doesNotContain("Read").doesNotContain("Delete");
        });
    }

    @Test
    void unchangedCapabilitiesSayNothing() {
        assertThat(builder.compare(
                description("Yes", "No", "Unknown"),
                description("Yes", "No", "Unknown"),
                "Edited.", List.of())).isEmpty();
    }
}
