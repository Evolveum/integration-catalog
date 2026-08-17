/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.object.CatalogUser;
import com.evolveum.midpoint.integration.catalog.object.Organization;
import com.evolveum.midpoint.integration.catalog.repository.CatalogUserRepository;
import com.evolveum.midpoint.integration.catalog.repository.OrganizationRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Turns a name the catalog stamped on a revision into contact addresses.
 *
 * <p>{@code integration_method.author} and {@code .maintainer} are free-text columns holding either
 * a username or an organization name - an organization contributor publishes on behalf of their
 * organization, so a maintainer is often not a person. Every name is therefore resolved as a person
 * first and as an organization second.
 *
 * <p>A name matching neither resolves to nothing rather than to an error: these columns keep the
 * value they had when the revision was written, even after the user or the organization is renamed
 * or removed, so a miss is expected rather than a fault.
 */
@Component
@RequiredArgsConstructor
public class CatalogContactResolver {

    private final CatalogUserRepository catalogUserRepository;
    private final OrganizationRepository organizationRepository;

    /**
     * Who should hear about work concerning one named party.
     *
     * @param addresses everyone to notify in their own right. A person is their own single address;
     *                  an organization contributes none, because the submission's author is the
     *                  member who speaks for it
     * @param fallback  address to fall back on when nobody on the submitting side could be reached,
     *                  which is the organization's shared mailbox. Null for a person, who has no
     *                  second address, and for an unknown name
     */
    public record NotificationTargets(List<String> addresses, String fallback) {

        private static final NotificationTargets NONE = new NotificationTargets(List.of(), null);

        /** Whether there is anyone at all to notify, by either route. */
        public boolean isEmpty() {
            return addresses.isEmpty() && fallback == null;
        }
    }

    /**
     * The address of the named party itself: their own for a person, the shared mailbox for an
     * organization. This is the address written next to a name in a work package's body, so it
     * answers "who is this" rather than "who should be notified" - use
     * {@link #notificationTargets(String)} for the latter.
     */
    public Optional<String> emailOf(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return person(name)
                .map(CatalogUser::getEmail)
                .flatMap(CatalogContactResolver::present)
                .or(() -> organization(name)
                        .map(Organization::getEmail)
                        .flatMap(CatalogContactResolver::present));
    }

    /**
     * The addresses to notify about work concerning {@code name}.
     *
     * <p>A person is their own single address.
     *
     * <p>An organization contributes no address of its own beyond a fallback, because a submission
     * maintained by an organization is represented by its author - the member who actually submitted
     * it, and the one who can act on what the review asks for. The membership at large is
     * deliberately not subscribed: most of it has nothing to do with this submission, and it would
     * include members who cannot even open it, since an {@code IndividualContributor} who belongs to
     * an organization acts as themselves rather than for the team (see
     * {@code AuthService#canEdit}).
     *
     * <p>The organization's own mailbox is therefore a last resort rather than one address among
     * many: it matters only when the author cannot be reached at all.
     */
    public NotificationTargets notificationTargets(String name) {
        if (name == null || name.isBlank()) {
            return NotificationTargets.NONE;
        }

        Optional<String> personEmail = person(name).map(CatalogUser::getEmail).flatMap(CatalogContactResolver::present);
        if (personEmail.isPresent()) {
            return new NotificationTargets(List.of(personEmail.get()), null);
        }

        Organization organization = organization(name).orElse(null);
        if (organization == null) {
            // Either a person the catalog no longer knows, or one who never had an address. Nothing
            // to notify; the name is still written in the work package's body.
            return NotificationTargets.NONE;
        }
        return new NotificationTargets(List.of(), present(organization.getEmail()).orElse(null));
    }

    private Optional<CatalogUser> person(String name) {
        return catalogUserRepository.findByUsername(name);
    }

    private Optional<Organization> organization(String name) {
        return organizationRepository.findByNameIgnoreCase(name);
    }

    /** An address only counts when it is actually there: the column is nullable and free text. */
    private static Optional<String> present(String email) {
        return email == null || email.isBlank() ? Optional.empty() : Optional.of(email.trim());
    }
}
