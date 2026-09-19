/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.MaintainerDto;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.evolveum.midpoint.integration.catalog.repository.AuthorRepository;
import com.evolveum.midpoint.integration.catalog.repository.MaintainerRepository;
import com.evolveum.midpoint.integration.catalog.security.CatalogClaims;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Records on a catalog item who owns it, at the moment it is written.
 */
@Slf4j
@Service
public class OwnershipService {

    private static final String DEFAULT_LABEL_EVOLVEUM = "Evolveum";
    private static final String DEFAULT_LABEL_COMMUNITY = "Community";

    private final OrganizationService organizationService;
    private final CatalogClaims claims;
    private final MaintainerRepository maintainerRepository;
    private final AuthorRepository authorRepository;

    public OwnershipService(OrganizationService organizationService, CatalogClaims claims, MaintainerRepository maintainerRepository, AuthorRepository authorRepository) {
        this.organizationService = organizationService;
        this.claims = claims;
        this.maintainerRepository = maintainerRepository;
        this.authorRepository = authorRepository;
    }

    /**
     * Stamps a newly written item: {@code username} authored it, and {@code requestedMaintainer}
     * (a category and what that category needs, as chosen in the publish form) maintains it. The
     * author is recorded for the audit trail only - {@link AuthService} grants nothing for it.
     */
    public void stampNew(SetOwnership item, String username, MaintainerDto requestedMaintainer) {
        OidcUser caller = currentOidcUser();
//        String callerRole = caller != null ? claims.effectiveRole(caller) : null;
//        Organization callerOrganization = caller != null
//                ? organizationService.getOrganizationByName(claims.organizationName(caller))
//                : null;
        String mail = caller != null ? caller.getEmail() : null;

        Author author = authorRepository.findByUsername(username)
                .orElseGet(() -> authorRepository.save(new Author().setUsername(username).setEmail(mail)));
        item.setAuthor(author);

        assignMaintainer(item, requestedMaintainer);
    }

    /**
     * Sets the maintainer of an item that already exists, as chosen in the edit form. An existing
     * row is referenced by id; otherwise the category decides what is looked up or created - the
     * two catalog-wide rows are seeded and never created here.
     *
     * @throws IllegalArgumentException when the request names a maintainer that cannot be built
     * @throws IllegalStateException when a seeded row it asks for is missing from the database
     */
    public void assignMaintainer(SetOwnership item, MaintainerDto requestedMaintainer) {
        if (requestedMaintainer == null) {
            throw new IllegalArgumentException("An item cannot be written without a maintainer");
        }
        if (requestedMaintainer.id() != null) {
            Optional<Maintainer> maintainer = maintainerRepository.findById(requestedMaintainer.id());
            if (maintainer.isPresent()) {
                item.setMaintainer(maintainer.get());
                return;
            }
        }

        Maintainer maintainer;

        switch (requestedMaintainer.category()) {
            case null -> throw new IllegalArgumentException(
                    "The requested maintainer carries no category");
            case EVOLVEUM -> maintainer = seededMaintainer(MaintainerType.EVOLVEUM);
            case COMMUNITY -> maintainer = seededMaintainer(MaintainerType.COMMUNITY);
            case ORG -> {
                Organization organization = organizationOf(requestedMaintainer.organizationName());
                if (organization == null) {
                    throw new IllegalArgumentException(
                            "No organization is named " + requestedMaintainer.organizationName());
                }

                // One row per organization: the second item it maintains reuses the first one's row,
                // which is what ux_maintainer_org enforces.
                maintainer = maintainerRepository
                        .findByCategoryAndOrganization(MaintainerType.ORG, organization)
                        .orElseGet(() -> new Maintainer()
                                .setOrganization(organization)
                                .setCategory(MaintainerType.ORG));
            }
            case USER -> {
                String username = requestedMaintainer.username();
                if (StringUtils.isEmpty(username)) {
                    throw new IllegalArgumentException(
                            "A maintainer of category USER carries no username");
                }
                // One row per person, likewise - see ux_maintainer_user.
                maintainer = maintainerRepository
                        .findByCategoryAndUsernameIgnoreCase(MaintainerType.USER, username)
                        .orElseGet(() -> new Maintainer()
                                .setUsername(username)
                                .setCategory(MaintainerType.USER));
            }
        }

        maintainer = maintainerRepository.save(maintainer);
        item.setMaintainer(maintainer);
    }

    /**
     * The single row standing for a catalog-wide maintainer. Seeded with the schema, so its
     * absence is a broken database rather than a bad request.
     */
    private Maintainer seededMaintainer(MaintainerType category) {
        return maintainerRepository.findByCategory(category)
                .orElseThrow(() -> {
                    // Worth a line of its own: the request that hit it fails with a 500 naming a
                    // single row, which reads as a fluke until the log says the seed is missing.
                    log.error("The maintainers table holds no {} row. It is seeded with the schema,"
                            + " so the database was created or upgraded without its seed data and"
                            + " nothing can be published as {}.", category, category);
                    return new IllegalStateException(
                            "The maintainers table holds no " + category + " row; it is seeded with"
                                    + " the schema and every catalog-wide item refers to it");
                });
    }

    /**
     * The organization a request names. An name is what a read hands out, so it is tried first,
     * and a display name after it, which is what a form filled in by hand is likely to carry.
     */
    private Organization organizationOf(String organizationName) {
        if (StringUtils.isEmpty(organizationName)) {
            return null;
        }
        return organizationService.getOrganizationByName(organizationName);
    }

    /** A maintainer as the API carries it, labelled for display. */
    public MaintainerDto toDto(Maintainer maintainer) {
        if (maintainer == null) {
            return null;
        }
        return new MaintainerDto(
                maintainer.getId(),
                maintainer.getUsername(),
                maintainer.getOrganization() != null ? maintainer.getOrganization().getName() : null,
                maintainer.getCategory(),
                maintainerLabel(maintainer));
    }

    /**
     * What to show as an item's maintainer: the username, the organization's display name, or the
     * fixed Evolveum / Community label.
     */
    public String maintainerLabel(GetOwnershipOneMaintainer item) {
        return item != null ? maintainerLabel(item.getMaintainer()) : null;
    }

    public String maintainerLabel(Maintainer maintainer) {
        if (maintainer == null) {
            return null;
        }
        switch (maintainer.getCategory()) {
            case null -> throw new IllegalStateException(
                    "Maintainer " + maintainer.getId() + " has no category; the column is"
                            + " NOT NULL, so the row was written around the entity");
            case EVOLVEUM -> {
                return DEFAULT_LABEL_EVOLVEUM;
            }
            case COMMUNITY -> {
                return DEFAULT_LABEL_COMMUNITY;
            }
            case USER -> {
                return maintainer.getUsername();
            }
            case ORG -> {
                return organizationService.displayName(maintainer.getOrganization());
            }
        }
    }

    /**
     * Carries an item's whole ownership over to a new revision, draft or clone of it.
     */
    public void copyOwnership(GetOwnershipOneMaintainer from, SetOwnership to) {
        to.setAuthor(from.getAuthor());
        copyMaintainer(from, to);
    }

    public void copyOwnership(GetOwnershipListMaintainer from, SetOwnership to) {
        to.setAuthor(from.getAuthor());
        copyMaintainer(from, to);
    }

    /**
     * Carries only the maintainer over, for a flow that keeps the target's own author.
     */
    public void copyMaintainer(GetOwnershipOneMaintainer from, SetOwnership to) {
        to.setMaintainer(from.getMaintainer());
    }

    public void copyMaintainer(GetOwnershipListMaintainer from, SetOwnership to) {
        from.getMaintainer().forEach(to::setMaintainer);
    }

    private static OidcUser currentOidcUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof OidcUser oidcUser
                ? oidcUser
                : null;
    }
}
