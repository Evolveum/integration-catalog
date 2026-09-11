/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.security;

import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientPropertiesMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * A {@link ClientRegistrationRepository} that defers OIDC discovery until the first
 * request that needs it (login or logout), instead of at application startup.
 */
@Component
@EnableConfigurationProperties(OAuth2ClientProperties.class)
public class LazyClientRegistrationRepository implements ClientRegistrationRepository {

    private final OAuth2ClientProperties properties;

    private volatile Map<String, ClientRegistration> registrations;

    public LazyClientRegistrationRepository(OAuth2ClientProperties properties) {
        this.properties = properties;
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        return registrations().get(registrationId);
    }

    private Map<String, ClientRegistration> registrations() {
        Map<String, ClientRegistration> current = registrations;
        if (current == null) {
            synchronized (this) {
                current = registrations;
                if (current == null) {
                    current = new OAuth2ClientPropertiesMapper(properties).asClientRegistrations();
                    registrations = current;
                }
            }
        }
        return current;
    }
}
