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
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Records on a catalog item who owns it, at the moment it is written.
 */
@Service
public class OwnershipService {

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
     * (a username or an organization name, as chosen in the publish form) maintains it.
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

        //TODO this isn't true, user can be member of group but he can have only read_only role
        //TODO why we need it?
//        item.setAuthorOrgId(CatalogRole.ORGANIZATION_CONTRIBUTOR.equals(callerRole)
//                ? callerOrganizationId
//                : null);
//        item.setAuthorCategory(CatalogRole.categoryOf(callerRole));
//        item.setAuthorEmail(caller != null ? caller.getEmail() : null);
        assignMaintainer(item, requestedMaintainer);
    }

    /**
     * Sets the maintainer of an item that already exists, as chosen in the edit form. An
     * organization name becomes an organization reference; a username stays a username, and
     * carries the caller's organization when they maintain on its behalf.
     */
    public void assignMaintainer(SetOwnership item, MaintainerDto requestedMaintainer) {
        if (requestedMaintainer.id() != null) {
            Optional<Maintainer> maintainer = maintainerRepository.findById(requestedMaintainer.id());
            if (maintainer.isPresent()) {
                item.setMaintainer(maintainer.get());
                return;
            }
        }

        Maintainer maintainer;

        switch (requestedMaintainer.category()) {
            case null -> {
                //TODO exception (has to exist)
                return;
            }
            case EVOLVEUM -> {
                Optional<Maintainer> maintainerEvolveum =
                        maintainerRepository.findByCategory(MaintainerType.EVOLVEUM);
                maintainer = maintainerEvolveum.orElseThrow(() -> {/*TODO exception (has to exist) and log*/ return null;});
            }
            case COMMUNITY -> {
                Optional<Maintainer> maintainerCommunity =
                        maintainerRepository.findByCategory(MaintainerType.COMMUNITY);
                maintainer = maintainerCommunity.orElseThrow(() -> {/*TODO exception (has to exist) and log*/ return null;});
            }
            case ORG -> {
                String organizationName = organizationService.displayNameOfName(requestedMaintainer.organizationName());
                if (StringUtils.isEmpty(organizationName)) {
                    //TODO exception
                    return;
                }

                Organization organization = organizationService.getOrganizationByName(organizationName);
                if (organization == null) {
                    //TODO exception
                    return;
                }

                maintainer = new Maintainer().setOrganization(organization).setCategory(MaintainerType.ORG);
            }
            case USER -> {
                if (StringUtils.isEmpty(requestedMaintainer.username())) {
                    //TODO exception
                    return;
                }
                maintainer = new Maintainer().setUsername(requestedMaintainer.username()).setCategory(MaintainerType.USER);
            }
        }

        maintainer = maintainerRepository.save(maintainer);
        item.setMaintainer(maintainer);
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

    //TODO not used
//    /**
//     * The organization to record for a maintainer who is a person. Only the caller's own can be
//     * known, so an item maintained by anyone else carries no maintaining organization.
//     */
//    private Integer organizationOfMaintainer(String maintainer) {
//        OidcUser caller = currentOidcUser();
//        if (caller == null || maintainer == null || maintainer.isBlank()) {
//            return null;
//        }
//        String callerName = caller.getPreferredUsername() != null
//                ? caller.getPreferredUsername()
//                : caller.getSubject();
//        if (callerName == null || !maintainer.trim().equalsIgnoreCase(callerName.trim())) {
//            return null;
//        }
//        return CatalogRole.ORGANIZATION_CONTRIBUTOR.equals(claims.effectiveRole(caller))
//                ? organizationService.idOfAlias(claims.organizationAlias(caller))
//                : null;
//    }

    private static OidcUser currentOidcUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof OidcUser oidcUser
                ? oidcUser
                : null;
    }
}
