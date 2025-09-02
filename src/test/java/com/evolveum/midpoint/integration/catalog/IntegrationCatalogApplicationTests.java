package com.evolveum.midpoint.integration.catalog;

import com.evolveum.midpoint.integration.catalog.controller.Controller;
import com.evolveum.midpoint.integration.catalog.service.ApplicationService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class IntegrationCatalogApplicationTests {

    @Autowired
    private Controller controller;

	@Test
	void contextLoads() {
	}

    @Test
    void uploadScipConnector() {

    }
}
