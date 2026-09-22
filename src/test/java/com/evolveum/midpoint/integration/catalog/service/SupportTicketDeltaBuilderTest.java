/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers what an edit is reported as in the work package: which differences are worth a comment, how
 * they read, and what is deliberately passed over.
 */
class SupportTicketDeltaBuilderTest {

    /** The arrow and ellipsis the builder renders, as escapes so the file stays ASCII. */
    private static final String ARROW = "→";
    private static final String ELLIPSIS = "…";

    private static final String LEAD = "The submission was edited.";

    private final SupportTicketDeltaBuilder builder = new SupportTicketDeltaBuilder();

    private Optional<String> compare(String before, String after) {
        return builder.compare(before, after, LEAD, List.of());
    }

    private static String body(String... lines) {
        return String.join("\n", lines);
    }

    // -- what is worth saying ------------------------------------------------------------------

    @Test
    void anUnchangedSubmissionIsNotCommentedOn() {
        String body = body("* **Name:** LDAP", "* **Revision:** 1.0");

        assertTrue(compare(body, body).isEmpty());
    }

    @Test
    void aChangedFieldIsReportedWithBothValues() {
        Optional<String> comment = compare("* **Name:** old", "* **Name:** new");

        assertEquals(LEAD + "\n\n* **Name:** old " + ARROW + " new\n", comment.orElseThrow());
    }

    @Test
    void anAddedFieldSaysSoRatherThanShowingAnEmptyBefore() {
        Optional<String> comment = compare("* **Name:** LDAP",
                body("* **Name:** LDAP", "* **Licence:** Apache 2.0"));

        assertTrue(comment.orElseThrow().contains("* **Licence:** added - Apache 2.0"));
    }

    @Test
    void aRemovedFieldKeepsTheValueItHad() {
        Optional<String> comment = compare(body("* **Name:** LDAP", "* **Licence:** Apache 2.0"),
                "* **Name:** LDAP");

        assertTrue(comment.orElseThrow().contains("* **Licence:** removed, was Apache 2.0"));
    }

    /**
     * The catalog link carries the revision, so it changes on every edit while pointing at the same
     * submission - a line that would be in every comment and mean nothing.
     */
    @Test
    void theCatalogLinkChangingOnItsOwnSaysNothing() {
        Optional<String> comment = compare("* **Open in the catalog:** /methods/1.0",
                "* **Open in the catalog:** /methods/1.1");

        assertTrue(comment.isEmpty());
    }

    /** An edit bumps the revision whether or not it changed anything else. */
    @Test
    void aRevisionBumpAloneSaysNothing() {
        Optional<String> comment = compare("* **Revision:** 1.0", "* **Revision:** 1.1");

        assertTrue(comment.isEmpty());
    }

    @Test
    void aRevisionBumpIsListedOnceSomethingElseChanged() {
        Optional<String> comment = compare(body("* **Revision:** 1.0", "* **Name:** old"),
                body("* **Revision:** 1.1", "* **Name:** new"));

        String rendered = comment.orElseThrow();
        assertTrue(rendered.contains("* **Revision:** 1.0 " + ARROW + " 1.1"));
        assertTrue(rendered.contains("* **Name:** old " + ARROW + " new"));
    }

    // -- sections ------------------------------------------------------------------------------

    @Test
    void aNewSectionIsReportedAsAddedRatherThanFieldByField() {
        Optional<String> comment = compare("## Method\n* **Name:** LDAP",
                body("## Method", "* **Name:** LDAP", "## Connector", "* **Class:** LdapConnector"));

        String rendered = comment.orElseThrow();
        assertTrue(rendered.contains("**Connector**"));
        assertTrue(rendered.contains("* Added to the submission."));
        assertFalse(rendered.contains("* **Class:**"));
    }

    @Test
    void aDroppedSectionIsReportedAsRemoved() {
        Optional<String> comment = compare(
                body("## Method", "* **Name:** LDAP", "## Connector", "* **Class:** LdapConnector"),
                "## Method\n* **Name:** LDAP");

        assertTrue(comment.orElseThrow().contains("* No longer part of the submission."));
    }

    /**
     * The heading says both which connector it is and whether this submission publishes it, so the
     * two are split: publishing it is a changed field, not one connector gone and another arrived.
     */
    @Test
    void aConnectorBecomingPublishedIsAFieldChangeNotTwoSections() {
        Optional<String> comment = compare("### LDAP - already published",
                "### LDAP - to be published with this method");

        String rendered = comment.orElseThrow();
        assertTrue(rendered.contains("**LDAP**"));
        assertTrue(rendered.contains("* **In this submission:** already published " + ARROW
                + " to be published with this method"));
        assertFalse(rendered.contains("Added to the submission."));
        assertFalse(rendered.contains("No longer part of the submission."));
    }

    /** Two connectors of one bundle legitimately share a heading, so the second is numbered. */
    @Test
    void sectionsSharingATitleAreKeptApart() {
        Optional<String> comment = compare(
                body("### LDAP", "* **Class:** First", "### LDAP", "* **Class:** Second"),
                body("### LDAP", "* **Class:** First", "### LDAP", "* **Class:** Changed"));

        String rendered = comment.orElseThrow();
        assertTrue(rendered.contains("**LDAP (2)**"));
        assertTrue(rendered.contains("* **Class:** Second " + ARROW + " Changed"));
    }

    // -- fields and values ---------------------------------------------------------------------

    /** An indented bullet belongs to the bullet above it, which carries no value of its own. */
    @Test
    void anIndentedFieldIsLabelledUnderItsParent() {
        Optional<String> comment = compare(
                body("* **Bundle:**", "  * **Name:** old"),
                body("* **Bundle:**", "  * **Name:** new"));

        assertTrue(comment.orElseThrow().contains("* **Bundle / Name:** old " + ARROW + " new"));
    }

    @Test
    void aValueClearedToNothingReadsAsEmpty() {
        Optional<String> comment = compare(
                body("* **Bundle:**", "  * **Homepage:** https://example.org"),
                body("* **Bundle:**", "  * **Homepage:**"));

        assertTrue(comment.orElseThrow()
                .contains("* **Bundle / Homepage:** https://example.org " + ARROW + " _empty_"));
    }

    @Test
    void aValueTooLongToQuoteIsCutShort() {
        String tooLong = "x".repeat(400);

        Optional<String> comment = compare("* **Notes:** short", "* **Notes:** " + tooLong);

        String rendered = comment.orElseThrow();
        assertTrue(rendered.contains("x".repeat(300) + ELLIPSIS));
        assertFalse(rendered.contains("x".repeat(301)));
    }

    /** Prose has no label of its own, but a changed paragraph still has to be reported. */
    @Test
    void aChangedParagraphIsReportedAsText() {
        Optional<String> comment = compare("## Method\nConnects to LDAP.",
                "## Method\nConnects to LDAP and AD.");

        assertTrue(comment.orElseThrow()
                .contains("* **Text:** Connects to LDAP. " + ARROW + " Connects to LDAP and AD."));
    }

    /** The notes explain the work package rather than describe the submission. */
    @Test
    void theWorkPackagesOwnNotesAreNotCompared() {
        String note = SupportTicketDescriptionBuilder.NOTES.get(0);

        Optional<String> comment = compare("* **Name:** LDAP", body("* **Name:** LDAP", note));

        assertTrue(comment.isEmpty());
    }

    // -- attachments ---------------------------------------------------------------------------

    /** The tutorial is attached rather than written into the body, so only the caller knows. */
    @Test
    void fileChangesAreReportedThoughTheBodyIsUnchanged() {
        String body = "* **Name:** LDAP";

        Optional<String> comment =
                builder.compare(body, body, LEAD, List.of("Added install.pdf", "Removed old.pdf"));

        String rendered = comment.orElseThrow();
        assertTrue(rendered.contains("**Files**"));
        assertTrue(rendered.contains("* Added install.pdf"));
        assertTrue(rendered.contains("* Removed old.pdf"));
    }

    /** A first submission has no earlier description to compare against. */
    @Test
    void fileChangesStandAloneWhenThereIsNoEarlierDescription() {
        Optional<String> comment = builder.compare(null, "* **Name:** LDAP", LEAD,
                List.of("Added install.pdf"));

        String rendered = comment.orElseThrow();
        assertTrue(rendered.contains("* Added install.pdf"));
        assertFalse(rendered.contains("* **Name:**"));
    }

    @Test
    void nothingIsSaidWhenThereIsNeitherAComparisonNorAFileChange() {
        assertTrue(builder.compare(null, "* **Name:** LDAP", LEAD, List.of()).isEmpty());
    }
}
