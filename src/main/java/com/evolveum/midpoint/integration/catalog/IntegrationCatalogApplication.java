/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog;

import com.evolveum.midpoint.integration.catalog.configuration.GithubProperties;
import com.evolveum.midpoint.integration.catalog.configuration.JenkinsProperties;
import com.evolveum.midpoint.integration.catalog.repository.ApplicationRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties({GithubProperties.class, JenkinsProperties.class})
public class IntegrationCatalogApplication {

	public static void main(String[] args) {
		SpringApplication.run(IntegrationCatalogApplication.class, args);
	}

	//check if DB is connected
	@Bean
	CommandLineRunner pingDb(ApplicationRepository repo) {
		return args -> System.out.println("✅ Applications in DB: " + repo.count());
	}
}
