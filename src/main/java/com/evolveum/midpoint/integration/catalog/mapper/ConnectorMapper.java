/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.mapper;

import com.evolveum.midpoint.integration.catalog.common.PrivateKeyProvider;
import com.evolveum.midpoint.integration.catalog.dto.*;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Component
public class ConnectorMapper {

    private final PrivateKeyProvider privateKeyProvider;

    public ConnectorMapper(PrivateKeyProvider privateKeyProvider) {
        this.privateKeyProvider = privateKeyProvider;
    }

    // ── ActiveConnectorDto mapping ────────────────────────────────────────────

    public SignedActiveConnectorDto toActiveConnectorDto(ConnectorVersion connectorVersion) throws Exception {
        ActiveConnectorDto activeConnector = new ActiveConnectorDto(
                connectorVersion.getConnector().getFullyQualifiedClassName(),
                connectorVersion.getConnectorBundleVersion().getBundleVersion(),
                connectorVersion.getConnector().getConnectorBundle().getBundleName()
        );

        String signature = sign(activeConnector);

        return new SignedActiveConnectorDto(
                activeConnector,
                "key-2026-01",
                signature
        );
    }

    private String sign(ActiveConnectorDto dto) throws NoSuchAlgorithmException, InvalidKeyException, JsonProcessingException, SignatureException {
        Signature signature = Signature.getInstance("Ed25519");
        PrivateKey privateKey = privateKeyProvider.getPrivateKey();
        if (privateKey == null) {
            throw new IllegalArgumentException("Couldn't get private key for signing");
        }
        signature.initSign(privateKey);

        signature.update(toJson(dto));

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(signature.sign());
    }

    private byte[] toJson(ActiveConnectorDto dto) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.writeValueAsBytes(dto);
    }
}
