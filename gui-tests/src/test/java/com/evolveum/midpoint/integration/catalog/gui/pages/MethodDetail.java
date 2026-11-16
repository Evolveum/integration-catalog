/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui.pages;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.evolveum.midpoint.integration.catalog.gui.framework.Ui;
import com.microsoft.playwright.Download;

/** The detail page of one integration method version; the tests' methods have one connector. */
public final class MethodDetail {

    private final Ui ui;

    public MethodDetail(Ui ui) {
        this.ui = ui;
    }

    public void expectConnectorVersion(String version) {
        assertThat(ui.element("method-detail-connector-version")).hasText(version);
    }

    public void expectConnectorDescription(String description) {
        assertThat(ui.element("method-detail-connector-description")).containsText(description);
    }

    /** Downloads the method's ZIP bundle: the tutorial, its files and the connector build files. */
    public DownloadedBundle download() {
        Download download = ui.download("method-detail-download");
        // The setup steps shown after a download cover the page until they are dismissed.
        ui.click("download-info-understood");
        ui.expectHidden("download-info-understood");
        List<String> entries = new ArrayList<>();
        try (ZipFile zip = new ZipFile(download.path().toFile())) {
            zip.stream().map(ZipEntry::getName).forEach(entries::add);
        } catch (IOException e) {
            throw new UncheckedIOException("The downloaded bundle is not a readable ZIP", e);
        }
        return new DownloadedBundle(download.suggestedFilename(), entries);
    }

    /** @param entries the paths inside the ZIP, e.g. {@code connectors/x.jar} or {@code ERROR.txt} */
    public record DownloadedBundle(String fileName, List<String> entries) {

        public boolean hasConnectorJar() {
            return entries.stream().anyMatch(entry -> entry.startsWith("connectors/") && entry.endsWith(".jar"));
        }
    }
}
