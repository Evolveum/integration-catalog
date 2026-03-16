/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog;

import com.evolveum.midpoint.integration.catalog.configuration.GithubProperties;
import com.evolveum.midpoint.integration.catalog.configuration.JenkinsProperties;
import com.evolveum.midpoint.integration.catalog.configuration.LogoStorageProperties;
import com.evolveum.midpoint.integration.catalog.repository.ApplicationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@Slf4j
@SpringBootApplication
@EnableConfigurationProperties({GithubProperties.class, JenkinsProperties.class, LogoStorageProperties.class})
public class IntegrationCatalogApplication {

	public static void main(String[] args) {
		SpringApplication.run(IntegrationCatalogApplication.class, args);
	}

	// Check if DB is connected
	@Bean
	CommandLineRunner pingDb(ApplicationRepository repo) {
		return args -> log.info("Applications in DB: {}", repo.count());
	}
}
