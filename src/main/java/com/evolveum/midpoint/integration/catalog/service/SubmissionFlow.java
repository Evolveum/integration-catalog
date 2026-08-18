/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a submitting user did to put a revision in front of a reviewer, and therefore what the support
 * work package is called.
 *
 * <p>Carried on {@link IntegrationMethodSubmittedEvent} rather than worked out from the saved rows.
 * Each place that raises the event knows which action it is serving - the publish form only ever
 * creates, and {@code EditIntegrationMethodDto.minorBump()} is the "Save" / "Save as new version"
 * button itself - so passing it along keeps the one fact that only the caller has, instead of
 * reconstructing it downstream from revision numbers and lifecycle states.
 *
 * <p>Nothing else about the work package varies by flow. Whether the application and each connector
 * are being published alongside the method is read from their own lifecycle state, which is what the
 * approve step keys on too, so the body needs no branch per flow.
 */
public enum SubmissionFlow {

    /** First submission of a method, from the publish form. */
    CREATE("Create new IM"),

    /**
     * "Save": a correction. Either rewritten in place on a draft still in review, or forked off a
     * published revision as a minor bump that replaces it once approved.
     */
    EDIT("Edit existing IM"),

    /**
     * "Save as new version": a major bump that stands beside the published revision rather than
     * replacing it. Adding a connector to a published revision lands here as well - it forks the
     * same kind of draft, so a reviewer is being asked the same question.
     */
    UPGRADE("Upgrade to new IM version");

    private final String title;

    SubmissionFlow(String title) {
        this.title = title;
    }

    /**
     * Subject of the work package, e.g. {@code Review: Create new IM - "LDAP provisioning"}.
     *
     * @param methodName display name of the submitted method; a blank one falls back to a generic
     *                   word rather than leaving empty quotes in the subject
     */
    public String taskName(String methodName) {
        return "Review: " + title + " - \"" + displayed(methodName) + "\"";
    }

    /**
     * An existing subject with a different method name in it, for a submission renamed while it is
     * under review.
     *
     * <p>Only the name is rewritten: which flow opened the work package is what the reviewer was
     * originally asked, and correcting a submission does not turn a creation into an edit. A subject
     * that is not shaped like {@link #taskName} - renamed by hand in the portal - is left as the portal
     * has it, since whoever wrote it meant it.
     */
    public static String renamed(String subject, String methodName) {
        if (subject == null || subject.isBlank()) {
            return null;
        }
        Matcher matcher = SUBJECT.matcher(subject);
        return matcher.matches() ? matcher.group(1) + displayed(methodName) + "\"" : subject;
    }

    /** Shape of a subject this class writes, split before the quoted method name. */
    private static final Pattern SUBJECT = Pattern.compile("^(.* - \")(.*)\"$");

    /** A blank name falls back to a generic word rather than leaving empty quotes in the subject. */
    private static String displayed(String methodName) {
        return methodName == null || methodName.isBlank() ? "integration method" : methodName;
    }
}
