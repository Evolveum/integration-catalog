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
 * <p>
 * Spring Boot's auto-configured repository resolves the provider {@code issuer-uri}
 * (a call to the provider's {@code /.well-known/openid-configuration}) while the bean is
 * being created, which makes the whole application fail to start when the provider is not
 * running. Declaring this bean makes the auto-configuration back off; the registrations
 * are built from the exact same {@code spring.security.oauth2.client.*} properties via
 * Boot's own mapper, just lazily. A discovery failure is not cached: while the provider is
 * unreachable every login attempt retries, and the rest of the application (anonymous
 * browsing, existing sessions) keeps working.
 * <p>
 * {@code @EnableConfigurationProperties} is needed here because the backed-off
 * auto-configuration is also what normally registers the {@link OAuth2ClientProperties}
 * binding.
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
