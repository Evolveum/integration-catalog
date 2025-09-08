package com.evolveum.midpoint.integration.catalog.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jenkins")
public record JenkinsProperties(
        String url,
        String apiToken,
        String username
) {
}
