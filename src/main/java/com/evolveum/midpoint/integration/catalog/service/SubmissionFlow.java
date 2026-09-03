/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the submitting user did to put a revision in front of a reviewer, which names the support
 * work package. Carried on the event because only the caller knows it.
 */
public enum SubmissionFlow {

    /** First submission of a method, from the publish form. */
    CREATE("Create new IM"),

    /** "Save": a correction, in place on a draft or as a minor bump off a published revision. */
    EDIT("Edit existing IM"),

    /** "Save as new version": a major bump standing beside the published revision. */
    UPGRADE("Upgrade to new IM version");

    private final String title;

    SubmissionFlow(String title) {
        this.title = title;
    }

    /**
     * Subject of the work package, e.g. {@code Create new IM - "LDAP provisioning"}.
     */
    public String taskName(String methodName) {
        return title + " - \"" + displayed(methodName) + "\"";
    }

    /**
     * An existing subject with a different method name in it, for a submission renamed under review.
     * Only the name is rewritten, and a subject not shaped like {@link #taskName} is left alone.
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
