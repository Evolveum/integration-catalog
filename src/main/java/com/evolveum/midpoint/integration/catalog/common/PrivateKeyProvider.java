/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.common;

import ch.qos.logback.classic.Logger;
import com.evolveum.midpoint.integration.catalog.configuration.ConnectorSigningProperties;
import lombok.Getter;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;

@Getter
@Component
public class PrivateKeyProvider {

    private static final Logger LOGGER = (Logger) LoggerFactory.getLogger(PrivateKeyProvider.class);


    private final PrivateKey privateKey;

    public PrivateKeyProvider(ConnectorSigningProperties properties) {
        privateKey = loadPrivateKey(properties);
    }

    private PrivateKey loadPrivateKey(ConnectorSigningProperties properties) {
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");

            InputStream is = Files.newInputStream(Path.of(properties.keystore()));
            keyStore.load(is, properties.password().toCharArray());

            return (PrivateKey) keyStore.getKey(
                    properties.alias(),
                    properties.password().toCharArray());
        } catch (Exception e) {
            LOGGER.error("Couldn't' load private key for connector signing.", e);
        }
        return null;
    }

}
