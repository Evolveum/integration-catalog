/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.LoginResponseDto;
import com.evolveum.midpoint.integration.catalog.object.CatalogUser;
import com.evolveum.midpoint.integration.catalog.object.Organization;
import com.evolveum.midpoint.integration.catalog.repository.CatalogUserRepository;
import com.evolveum.midpoint.integration.catalog.repository.OrganizationRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private final CatalogUserRepository catalogUserRepository;
    private final OrganizationRepository organizationRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthService(CatalogUserRepository catalogUserRepository, OrganizationRepository organizationRepository) {
        this.catalogUserRepository = catalogUserRepository;
        this.organizationRepository = organizationRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    public Optional<LoginResponseDto> login(String username, String password) {
        Optional<CatalogUser> userOpt = catalogUserRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            return Optional.empty();
        }

        CatalogUser user = userOpt.get();
        if (!passwordEncoder.matches(password, user.getPassword())) {
            return Optional.empty();
        }

        Organization org = user.getOrganization();
        return Optional.of(new LoginResponseDto(
                user.getUsername(),
                user.getRole(),
                org != null ? org.getId() : null,
                org != null ? org.getName() : null
        ));
    }

    public List<String> getAllMaintainers() {
        List<String> result = new ArrayList<>();
        catalogUserRepository.findAll().stream()
                .map(CatalogUser::getUsername)
                .forEach(result::add);
        organizationRepository.findAll().stream()
                .map(Organization::getName)
                .forEach(result::add);
        return result;
    }

    /**
     * Whether {@code username} may see/modify an item designated by {@code maintainer} and
     * uploaded by {@code author}.
     * <p>
     * This is the authoritative access check, mirrored (for UX only) on the client:
     * <ul>
     *   <li>Superuser may access anything;</li>
     *   <li>the designated maintainer may access it — matched either by username
     *       (maintainer == username) or by organization (maintainer == the caller's
     *       organization name, i.e. the item is maintained by the caller's org);</li>
     *   <li>the uploader may access items they authored (author == username);</li>
     *   <li>an Organization contributor may access any item authored by a member of their
     *       own organization.</li>
     * </ul>
     * The {@code maintainer} is the primary ownership signal: it is explicitly set when
     * publishing (e.g. a superuser may attribute an item to another user or org), whereas
     * {@code author} merely records who uploaded it. An unknown or anonymous user is never
     * granted access (except a superuser).
     */
    public boolean canEdit(String username, String author, String maintainer) {
        if (username == null || username.isBlank()) {
            return false;
        }
        CatalogUser caller = catalogUserRepository.findByUsername(username).orElse(null);
        if (caller == null) {
            return false;
        }
        if ("Superuser".equals(caller.getRole())) {
            return true;
        }
        // Maintainer designates ownership: match by the caller's username or by their org name.
        if (maintainer != null && !maintainer.isBlank()) {
            if (maintainer.equalsIgnoreCase(username)) {
                return true;
            }
            if (caller.getOrganization() != null && caller.getOrganization().getName() != null
                    && maintainer.equalsIgnoreCase(caller.getOrganization().getName())) {
                return true;
            }
        }
        // The uploader keeps access, as do organization contributors over their org's uploads.
        if (author != null && author.equalsIgnoreCase(username)) {
            return true;
        }
        if ("OrganizationContributor".equals(caller.getRole())
                && caller.getOrganization() != null && author != null) {
            CatalogUser owner = catalogUserRepository.findByUsername(author).orElse(null);
            if (owner != null && owner.getOrganization() != null
                    && caller.getOrganization().getId().equals(owner.getOrganization().getId())) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code username} resolves to a Superuser. Used to gate approval actions. */
    public boolean isSuperuser(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        return catalogUserRepository.findByUsername(username)
                .map(u -> "Superuser".equals(u.getRole()))
                .orElse(false);
    }

    public List<String> getOrganizationMembers(String username) {
        return catalogUserRepository.findByUsername(username)
                .map(user -> {
                    if (user.getOrganization() == null) {
                        return List.of(username);
                    }
                    return catalogUserRepository
                            .findByOrganizationId(user.getOrganization().getId())
                            .stream()
                            .map(CatalogUser::getUsername)
                            .collect(Collectors.toList());
                })
                .orElse(List.of(username));
    }
}
