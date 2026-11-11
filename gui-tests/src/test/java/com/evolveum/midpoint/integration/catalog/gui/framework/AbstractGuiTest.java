/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui.framework;

import java.nio.file.Path;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Gives every test a fresh browser session, and keeps a screenshot and a trace of a failed one in
 * {@code target/gui-tests}, because the tests run without a visible browser.
 */
public abstract class AbstractGuiTest {

    private static final Path OUTPUT = Path.of("target", "gui-tests");
    /** For actions and assertions alike; raise it for a slow environment instead of changing tests. */
    private static final double TIMEOUT_MS = Double.parseDouble(System.getProperty("gui.timeoutMs", "20000"));

    /** The defaults are users of keycloak_for_auth/import/integration-catalog-realm.json. */
    protected static final Account SUPERUSER = Account.fromProperties("superuser", "u5");
    protected static final Account CONTRIBUTOR = Account.fromProperties("contributor", "u3");
    protected static final Account READ_ONLY = Account.fromProperties("readOnly", "u2");

    private static final String BASE_URL = System.getProperty("gui.baseUrl", "http://localhost:4200");
    private static final boolean HEADLESS = Boolean.parseBoolean(System.getProperty("gui.headless", "true"));

    private static Playwright playwright;
    private static Browser browser;

    private BrowserContext context;
    protected Page page;
    protected Ui ui;

    /** Runs before {@code @AfterEach}, while the page of the failed test is still open. */
    @RegisterExtension
    final AfterTestExecutionCallback keepFailureEvidence = extension -> {
        if (extension.getExecutionException().isPresent()) {
            String name = extension.getRequiredTestClass().getSimpleName() + "-" + extension.getRequiredTestMethod().getName();
            page.screenshot(new Page.ScreenshotOptions().setPath(OUTPUT.resolve(name + ".png")).setFullPage(true));
            context.tracing().stop(new Tracing.StopOptions().setPath(OUTPUT.resolve(name + "-trace.zip")));
        } else {
            context.tracing().stop();
        }
    };

    @BeforeAll
    static void startBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(HEADLESS));
        PlaywrightAssertions.setDefaultAssertionTimeout(TIMEOUT_MS);
    }

    @AfterAll
    static void stopBrowser() {
        playwright.close();
    }

    @BeforeEach
    void openSession() {
        context = newContext();
        context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true));
        page = context.newPage();
        ui = new Ui(page);
    }

    @AfterEach
    void closeSession() {
        context.close();
    }

    /** A separate session, e.g. for what an anonymous visitor sees next to a signed-in user. */
    protected BrowserContext newContext() {
        BrowserContext created = browser.newContext(new Browser.NewContextOptions()
                .setBaseURL(BASE_URL)
                .setViewportSize(1440, 900));
        created.setDefaultTimeout(TIMEOUT_MS);
        return created;
    }
}
