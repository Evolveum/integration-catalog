package com.evolveum.midpoint.integration.catalog.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Created by Dominik.
 */
@ConfigurationProperties(prefix = "github")
public record GithubProperties (
        String apiToken,
        String groupPath,
        String templatePath
) {
}
