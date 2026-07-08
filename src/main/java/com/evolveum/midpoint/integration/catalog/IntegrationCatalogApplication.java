/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog;

import ch.qos.logback.classic.Logger;
import com.evolveum.midpoint.integration.catalog.configuration.GithubProperties;
import com.evolveum.midpoint.integration.catalog.configuration.JenkinsProperties;
import com.evolveum.midpoint.integration.catalog.configuration.LogoStorageProperties;
import com.evolveum.midpoint.integration.catalog.configuration.TutorialStorageProperties;
import com.evolveum.midpoint.integration.catalog.repository.ApplicationRepository;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties({GithubProperties.class, JenkinsProperties.class, LogoStorageProperties.class, TutorialStorageProperties.class})
public class IntegrationCatalogApplication {

	private static final Logger LOG
			= (Logger) LoggerFactory.getLogger(IntegrationCatalogApplication.class);

	public static void main(String[] args) {
		SpringApplication.run(IntegrationCatalogApplication.class, args);
	}

	// Check if DB is connected
	@Bean
	CommandLineRunner pingDb(ApplicationRepository repo) {
		return args -> LOG.info("Applications in DB: {}", repo.count());
	}
}
