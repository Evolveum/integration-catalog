/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the terminal quiet once the application is running, without losing warnings/errors.
 * <p>
 * During startup the console ("stdout") appender has no level filter, so all initialization
 * logs are visible in the terminal (and saved to file). Once the application is fully
 * initialized ({@link ApplicationReadyEvent}), a {@link ThresholdFilter} set to {@code WARN}
 * is added to the console appender, so from then on only {@code WARN}/{@code ERROR} flash in
 * the terminal while {@code INFO}/{@code DEBUG} keep going to the file only. The file appender
 * is never touched, so the log file continues to capture everything.
 */
@Component
public class ConsoleLoggingToggle {

    private static final Logger LOG = (Logger) LoggerFactory.getLogger(ConsoleLoggingToggle.class);

    /** Must match the appender name in log4j.xml. */
    private static final String CONSOLE_APPENDER_NAME = "stdout";

    /** Minimum level allowed to reach the terminal after startup. */
    private static final String RUNTIME_CONSOLE_THRESHOLD = "WARN";

    @EventListener(ApplicationReadyEvent.class)
    public void raiseConsoleThresholdAfterStartup() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        Appender<ILoggingEvent> consoleAppender = rootLogger.getAppender(CONSOLE_APPENDER_NAME);

        if (consoleAppender == null) {
            LOG.warn("Console appender '{}' was not found on the root logger; terminal output was not changed.",
                    CONSOLE_APPENDER_NAME);
            return;
        }

        // Log the hand-off while the console still shows INFO, so this is the last
        // informational line the user sees in the terminal.
        LOG.info("Application fully initialized. Terminal now shows {}+ only; all logs continue to the file.",
                RUNTIME_CONSOLE_THRESHOLD);

        ThresholdFilter filter = new ThresholdFilter();
        filter.setLevel(RUNTIME_CONSOLE_THRESHOLD);
        filter.setContext(context);
        filter.start();
        consoleAppender.addFilter(filter);
    }
}
