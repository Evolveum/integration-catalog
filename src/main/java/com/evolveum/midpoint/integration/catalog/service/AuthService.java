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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

    private final CatalogUserRepository catalogUserRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthService(CatalogUserRepository catalogUserRepository) {
        this.catalogUserRepository = catalogUserRepository;
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
                org != null ? org.getId() : null,
                org != null ? org.getName() : null
        ));
    }
}
