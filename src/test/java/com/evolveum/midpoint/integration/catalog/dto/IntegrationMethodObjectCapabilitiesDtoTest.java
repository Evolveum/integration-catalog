/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import com.evolveum.midpoint.integration.catalog.object.CapabilityState;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationMethodObjectCapabilitiesDtoTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static IntegrationMethodObjectCapabilitiesDto object(String objectClass, CapabilityState... states) {
        List<IntegrationMethodCapabilityStateDto> capabilities = IntStream.range(0, states.length)
                .mapToObj(i -> new IntegrationMethodCapabilityStateDto("CAP_" + i, states[i]))
                .toList();
        return new IntegrationMethodObjectCapabilitiesDto(objectClass, capabilities);
    }

    @Test
    void objectWithOneYesIsValid() {
        assertThat(validator.validate(object("Account", CapabilityState.NO, CapabilityState.YES, CapabilityState.UNKNOWN)))
                .isEmpty();
    }

    @Test
    void objectWithoutYesIsRejected() {
        assertThat(validator.validate(object("Account", CapabilityState.NO, CapabilityState.UNKNOWN)))
                .extracting(v -> v.getMessage())
                .containsExactly("Every object needs at least one capability marked YES.");
    }

    @Test
    void objectWithoutCapabilitiesIsRejected() {
        assertThat(validator.validate(new IntegrationMethodObjectCapabilitiesDto("Account", null))).hasSize(1);
    }

    /** Unnamed objects are skipped on save, so there is nothing to reject. */
    @Test
    void unnamedObjectIsNotValidated() {
        assertThat(validator.validate(object("  ", CapabilityState.NO))).isEmpty();
    }

    @Test
    void editRequestValidatesEachObject() {
        EditIntegrationMethodDto edit = new EditIntegrationMethodDto(null, null, null, null, null,
                List.of(object("Account", CapabilityState.YES), object("Group", CapabilityState.NO)),
                false, false, null, null, null);

        assertThat(validator.validate(edit)).hasSize(1);
    }
}
