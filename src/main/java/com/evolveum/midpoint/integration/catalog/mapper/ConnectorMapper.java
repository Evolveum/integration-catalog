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

/**
 * Mapper responsible for converting connector domain objects into DTO representations.
 *
 * This mapper creates active connector DTOs and provides signing functionality for connector data using
 * the configured private key. The generated signatures allow consumers to verify the authenticity and
 * integrity of connector metadata.
 *
 * The mapper uses Ed25519 digital signatures and encodes the resulting signature using Base64.
 */
@Component
public class ConnectorMapper {

    private final PrivateKeyProvider privateKeyProvider;

    public ConnectorMapper(PrivateKeyProvider privateKeyProvider) {
        this.privateKeyProvider = privateKeyProvider;
    }

    /**
     * Converts a connector entity into a signed active connector DTO.
     *
     * The method creates an active connector representation containing the connector class name and bundle name,
     * generates a digital signature for the data, and attaches the signature metadata to the returned DTO.
     *
     * @param connector connector entity to be converted and signed
     * @return signed DTO representation of the active connector
     * @throws Exception if the connector data cannot be signed
     */
    public SignedActiveConnectorDto toActiveConnectorDto(Connector connector) throws Exception {
        ActiveConnectorDto activeConnector = new ActiveConnectorDto(
                connector.getFullyQualifiedClassName(),
                connector.getConnectorBundle().getBundleName()
        );

        String signature = sign(activeConnector);
        List<SignatureDto> signatures = new ArrayList<>();
        signatures.add(new SignatureDto(
                "key-2026-01",
                signature));

        return new SignedActiveConnectorDto(
                activeConnector,
                signatures
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

        return Base64.getEncoder()
                .encodeToString(signature.sign());
    }

    private byte[] toJson(ActiveConnectorDto dto) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.writeValueAsBytes(dto);
    }
}
