/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.object.CatalogRole;
import com.evolveum.midpoint.integration.catalog.object.CatalogUser;
import com.evolveum.midpoint.integration.catalog.object.Organization;
import com.evolveum.midpoint.integration.catalog.repository.CatalogUserRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Turns a name the catalog stamped on a revision into a contact address.
 *
 * <p>{@code integration_method.author} and {@code .maintainer} are free-text columns holding either a
 * username or an organization name - an organization contributor publishes on behalf of their
 * organization, so a maintainer is often not a person. Only people have an address
 * ({@code catalog_users.email}), so an organization name resolves to nothing here, and that costs the
 * catalog nothing: a submission maintained by an organization is represented by its author, who is a
 * member of that organization and the one who can act on what a review asks for.
 *
 * <p>A name matching nobody resolves to nothing rather than to an error: these columns keep the value
 * they had when the revision was written, even after the user is renamed or removed, so a miss is
 * expected rather than a fault.
 */
@Component
@RequiredArgsConstructor
public class CatalogContactResolver {

    private final CatalogUserRepository catalogUserRepository;

    /**
     * The address to reach the named party at - both for writing it next to their name in a support
     * work package and for subscribing them to one.
     *
     * @return their address, or empty for an organization, for somebody the catalog no longer knows,
     * and for somebody who never had one
     */
    public Optional<String> emailOf(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return catalogUserRepository.findByUsername(name)
                .map(CatalogUser::getEmail)
                // The column is nullable and free text, so an address only counts when it is there.
                .filter(email -> !email.isBlank())
                .map(String::trim);
    }

    /**
     * The organization the named person publishes on behalf of, for naming them as
     * {@code username (email), org}.
     *
     * <p>Only an {@link CatalogRole#ORGANIZATION_CONTRIBUTOR} has one to show. An
     * {@code IndividualContributor} who belongs to an organization publishes as themselves, so naming
     * their employer next to a submission would misattribute it - the same distinction
     * {@code ApplicationMapper} makes when it decides whether to show "org (username)" in the catalog.
     *
     * @return the organization's name, or empty for anyone else and for an organization name
     */
    public Optional<String> organizationOf(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return catalogUserRepository.findByUsername(name)
                .filter(user -> CatalogRole.ORGANIZATION_CONTRIBUTOR.matches(user.getRole()))
                .map(CatalogUser::getOrganization)
                .map(Organization::getName)
                .filter(organizationName -> !organizationName.isBlank());
    }
}
