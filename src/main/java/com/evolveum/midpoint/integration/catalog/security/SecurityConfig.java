/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.security;

import com.evolveum.midpoint.integration.catalog.configuration.JenkinsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import static com.evolveum.midpoint.integration.catalog.security.CatalogRole.INDIVIDUAL_CONTRIBUTOR;
import static com.evolveum.midpoint.integration.catalog.security.CatalogRole.ORGANIZATION_CONTRIBUTOR;
import static com.evolveum.midpoint.integration.catalog.security.CatalogRole.SUPERUSER;

/**
 * OIDC login against the identity provider (this application is the OIDC client) plus the endpoint
 * authorization matrix.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String ALL_API = "/api/**";

    private static final String CURRENT_USER = "/api/auth/me";

    private static final String ORGANIZATION_MEMBERS = "/api/auth/organization/members";

    private static final String MAINTAINER_DIRECTORY = "/api/auth/all-maintainers";

    private static final String[] REVIEW_DECISIONS = {
            "/api/applications/*/integration-method/*/*/start-review",
            "/api/applications/*/integration-method/*/*/stop-review",
            "/api/applications/*/integration-method/*/*/publish",
            "/api/applications/*/integration-method/*/*/reject" };

    private static final String CONNECTOR_UPLOADS = "/api/upload/**";

    private static final String INTEGRATION_METHOD = "/api/applications/*/integration-method/**";

    private static final String APPLICATION_LOGO = "/api/applications/*/logo";

    private static final String[] ITEM_ATTACHMENTS = {
            "/api/applications/*/integration-method/*/*/connectors",
            "/api/applications/*/integration-method/*/*/tutorial",
            APPLICATION_LOGO,
            "/api/integration-methods/*/tutorial" };

    private static final String REQUESTS = "/api/requests";

    private static final String SINGLE_REQUEST = "/api/requests/*";

    private static final String REQUEST_VOTE = "/api/requests/*/vote";

    private static final String RECENTLY_USED_ITEM = "/api/recently-used/*";

    private static final String[] CATALOG_SEARCHES = {
            "/api/applications/search/*/*",
            "/api/integration-methods/search/*/*" };

    private static final String LOGOUT = "/logout";

    private static final String POST_LOGOUT_REDIRECT = "{baseUrl}";

    private final CatalogOidcUserService catalogOidcUserService;
    private final JenkinsProperties jenkinsProperties;

    public SecurityConfig(CatalogOidcUserService catalogOidcUserService, JenkinsProperties jenkinsProperties) {
        this.catalogOidcUserService = catalogOidcUserService;
        this.jenkinsProperties = jenkinsProperties;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ClientRegistrationRepository clientRegistrationRepository) throws Exception {
        JenkinsCallbackFilter jenkinsCallbackFilter = new JenkinsCallbackFilter(jenkinsProperties);
        http
                // Reuses the MVC CORS mappings (configuration/CorsConfig) so preflights pass
                // the security chain when the SPA is served from another origin.
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .ignoringRequestMatchers(jenkinsCallbackFilter.authenticatedCallbackMatcher()))
                .addFilterBefore(jenkinsCallbackFilter, AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // Build callbacks (verify + continue/fail), reachable two ways: the
                        // Jenkins pipeline authenticates with the shared secret handled by the
                        // filter above, a contributor completing a build by hand through the
                        // manual-fill dialog with their session.
                        .requestMatchers(JenkinsCallbackFilter.CALLBACK_PATH_PATTERNS)
                                .hasAnyRole(INDIVIDUAL_CONTRIBUTOR, ORGANIZATION_CONTRIBUTOR, SUPERUSER,
                                        JenkinsCallbackFilter.CALLBACK_ROLE)

                        .requestMatchers(MAINTAINER_DIRECTORY).hasRole(SUPERUSER)
                        .requestMatchers(HttpMethod.POST, REVIEW_DECISIONS).hasRole(SUPERUSER)
                        .requestMatchers(CONNECTOR_UPLOADS)
                                .hasAnyRole(INDIVIDUAL_CONTRIBUTOR, ORGANIZATION_CONTRIBUTOR, SUPERUSER)
                        .requestMatchers(HttpMethod.PUT, INTEGRATION_METHOD)
                                .hasAnyRole(INDIVIDUAL_CONTRIBUTOR, ORGANIZATION_CONTRIBUTOR, SUPERUSER)
                        .requestMatchers(HttpMethod.DELETE, INTEGRATION_METHOD)
                                .hasAnyRole(INDIVIDUAL_CONTRIBUTOR, ORGANIZATION_CONTRIBUTOR, SUPERUSER)
                        .requestMatchers(HttpMethod.POST, ITEM_ATTACHMENTS)
                                .hasAnyRole(INDIVIDUAL_CONTRIBUTOR, ORGANIZATION_CONTRIBUTOR, SUPERUSER)
                        .requestMatchers(HttpMethod.DELETE, APPLICATION_LOGO)
                                .hasAnyRole(INDIVIDUAL_CONTRIBUTOR, ORGANIZATION_CONTRIBUTOR, SUPERUSER)
                        .requestMatchers(HttpMethod.POST, REQUESTS)
                                .hasAnyRole(INDIVIDUAL_CONTRIBUTOR, ORGANIZATION_CONTRIBUTOR, SUPERUSER)
                        .requestMatchers(HttpMethod.DELETE, SINGLE_REQUEST)
                                .hasAnyRole(INDIVIDUAL_CONTRIBUTOR, ORGANIZATION_CONTRIBUTOR, SUPERUSER)
                        .requestMatchers(HttpMethod.POST, REQUEST_VOTE).authenticated()
                        .requestMatchers(CURRENT_USER, ORGANIZATION_MEMBERS).authenticated()
                        .requestMatchers(HttpMethod.POST, RECENTLY_USED_ITEM).authenticated()
                        .requestMatchers(HttpMethod.GET, ALL_API).permitAll()
                        .requestMatchers(HttpMethod.POST, CATALOG_SEARCHES).permitAll()
                        .requestMatchers(ALL_API).authenticated()
                        .anyRequest().permitAll())
               .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        PathPatternRequestMatcher.withDefaults().matcher(ALL_API)))
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(catalogOidcUserService)))
                .logout(logout -> logout
                        // GET so the SPA can log out with a plain top-level navigation.
                        .logoutRequestMatcher(
                                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, LOGOUT))
                        .logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository)));
        return http.build();
    }

    /** RP-initiated logout: also ends the provider's SSO session, then returns to the app. */
    private LogoutSuccessHandler oidcLogoutSuccessHandler(ClientRegistrationRepository clientRegistrationRepository) {
        OidcClientInitiatedLogoutSuccessHandler handler =
                new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
        handler.setPostLogoutRedirectUri(POST_LOGOUT_REDIRECT);
        return handler;
    }
}
