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
 * OIDC login against Keycloak (this application is the OIDC client) plus the endpoint
 * authorization matrix.
 * <p>
 * The matrix in {@link #filterChain} is the authoritative list of what is public, what
 * requires login, and which role each operation needs. Data-dependent ownership rules
 * (may this contributor edit this particular item) stay in
 * {@link com.evolveum.midpoint.integration.catalog.service.AuthService#canEdit}, keyed off
 * the authenticated principal.
 * <p>
 * Sessions: the browser gets a session cookie after the OIDC code flow; the Angular app
 * calls /api with that cookie and mirrors the XSRF-TOKEN cookie into the X-XSRF-TOKEN
 * header for mutating requests. Unauthenticated /api requests get a plain 401 (no login
 * redirect); the SPA starts the login flow explicitly via /oauth2/authorization/keycloak.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final CatalogOidcUserService catalogOidcUserService;
    private final JenkinsProperties jenkinsProperties;

    public SecurityConfig(CatalogOidcUserService catalogOidcUserService, JenkinsProperties jenkinsProperties) {
        this.catalogOidcUserService = catalogOidcUserService;
        this.jenkinsProperties = jenkinsProperties;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ClientRegistrationRepository clientRegistrationRepository) throws Exception {
        http
                // Reuses the MVC CORS mappings (configuration/CorsConfig) so preflights pass
                // the security chain when the SPA is served from another origin.
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        // Machine callbacks authenticate with a shared secret, not a session.
                        .ignoringRequestMatchers(JenkinsCallbackFilter.CALLBACK_PATH_PATTERNS))
                .addFilterBefore(new JenkinsCallbackFilter(jenkinsProperties), AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // Jenkins build callbacks (verify + continue/fail) — guarded by the
                        // shared-secret JenkinsCallbackFilter above, not by a user session.
                        .requestMatchers(JenkinsCallbackFilter.CALLBACK_PATH_PATTERNS).permitAll()

                        // Superuser only: the review/approval workflow and the user directory.
                        .requestMatchers("/api/auth/all-maintainers").hasRole(SUPERUSER)
                        .requestMatchers(HttpMethod.POST,
                                "/api/applications/*/integration-method/*/*/start-review",
                                "/api/applications/*/integration-method/*/*/stop-review",
                                "/api/applications/*/integration-method/*/*/publish",
                                "/api/applications/*/integration-method/*/*/reject").hasRole(SUPERUSER)

                        // Contributors: publishing and editing catalog content. Ownership of the
                        // individual item is enforced on top of this by AuthService.canEdit.
                        .requestMatchers("/api/upload/**")
                                .hasAnyRole(INDIVIDUAL_CONTRIBUTOR, ORGANIZATION_CONTRIBUTOR, SUPERUSER)
                        .requestMatchers(HttpMethod.PUT, "/api/applications/*/integration-method/**")
                                .hasAnyRole(INDIVIDUAL_CONTRIBUTOR, ORGANIZATION_CONTRIBUTOR, SUPERUSER)
                        .requestMatchers(HttpMethod.DELETE, "/api/applications/*/integration-method/**")
                                .hasAnyRole(INDIVIDUAL_CONTRIBUTOR, ORGANIZATION_CONTRIBUTOR, SUPERUSER)
                        .requestMatchers(HttpMethod.POST,
                                "/api/applications/*/integration-method/*/*/connectors",
                                "/api/applications/*/integration-method/*/*/tutorial",
                                "/api/applications/*/logo",
                                "/api/integration-methods/*/tutorial")
                                .hasAnyRole(INDIVIDUAL_CONTRIBUTOR, ORGANIZATION_CONTRIBUTOR, SUPERUSER)
                        .requestMatchers(HttpMethod.DELETE, "/api/applications/*/logo")
                                .hasAnyRole(INDIVIDUAL_CONTRIBUTOR, ORGANIZATION_CONTRIBUTOR, SUPERUSER)

                        // Contributors: requests and voting. ReadOnly may only browse and
                        // download. Cancelling a request is further restricted to the requester
                        // or a superuser in ApplicationService.cancelRequest.
                        .requestMatchers(HttpMethod.POST,
                                "/api/requests",
                                "/api/requests/*/vote")
                                .hasAnyRole(INDIVIDUAL_CONTRIBUTOR, ORGANIZATION_CONTRIBUTOR, SUPERUSER)
                        .requestMatchers(HttpMethod.DELETE, "/api/requests/*")
                                .hasAnyRole(INDIVIDUAL_CONTRIBUTOR, ORGANIZATION_CONTRIBUTOR, SUPERUSER)

                        // Any authenticated user (including ReadOnly): profile and
                        // recently-used tracking.
                        .requestMatchers("/api/auth/me", "/api/auth/organization/members").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/recently-used/*").authenticated()

                        // Anonymous catalog browsing: all remaining reads and the two search POSTs.
                        .requestMatchers(HttpMethod.GET, "/api/**").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/applications/search/*/*",
                                "/api/integration-methods/search/*/*").permitAll()

                        // Deny-by-default for any other (or future) API endpoint.
                        .requestMatchers("/api/**").authenticated()

                        // Everything outside /api: the SPA, its assets, and the OAuth endpoints.
                        .anyRequest().permitAll())
                // XHR calls must see a 401, not a redirect to Keycloak; the SPA starts the
                // login flow itself by navigating to /oauth2/authorization/keycloak.
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(catalogOidcUserService)))
                .logout(logout -> logout
                        // GET so the SPA can log out with a plain top-level navigation.
                        .logoutRequestMatcher(
                                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/logout"))
                        .logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository)));
        return http.build();
    }

    /** RP-initiated logout: also ends the Keycloak SSO session, then returns to the app. */
    private LogoutSuccessHandler oidcLogoutSuccessHandler(ClientRegistrationRepository clientRegistrationRepository) {
        OidcClientInitiatedLogoutSuccessHandler handler =
                new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
        handler.setPostLogoutRedirectUri("{baseUrl}");
        return handler;
    }
}
