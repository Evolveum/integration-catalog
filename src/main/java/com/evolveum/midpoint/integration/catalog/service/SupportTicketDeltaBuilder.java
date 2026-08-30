/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Says what an edit changed about a submission, as a comment for its support work package, so a
 * reviewer does not have to re-read the whole ticket to find the corrected line.
 *
 * <p>Compared on the rendered description rather than on the entities: an in-place edit deletes the
 * row the earlier version was built from, so the portal's copy is all that is left of "before", and
 * it is by definition what the reviewer read. The description's own shape carries the structure, so
 * a field added to {@link SupportTicketDescriptionBuilder} is compared without a change here.
 */
@Component
public class SupportTicketDeltaBuilder {

    /** A field: {@code * **Label:** value}, optionally indented under the bullet above it. */
    private static final Pattern BULLET = Pattern.compile("^(\\s*)\\*\\s+\\*\\*(.+?):\\*\\*\\s*(.*?)\\s*$");

    /** A section: any markdown heading. */
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*?)\\s*$");

    /** A section too: a whole line in bold, which is how the bundle block is titled. */
    private static final Pattern BOLD_LINE = Pattern.compile("^\\*\\*(.+?)\\*\\*\\s*$");

    /**
     * A connector heading, whose text says both which connector it is and whether this submission
     * publishes it. Split so the connector keeps one section across a change of the latter.
     */
    private static final Pattern CONNECTOR_HEADING =
            Pattern.compile("^(.*\\S)\\s+-\\s+(already published|to be published with this method)$");

    /** Field the {@link #CONNECTOR_HEADING} suffix is compared as, once split off the section name. */
    private static final String STATUS_FIELD = "In this submission";

    /** Field a section's prose is compared as, since it has no label of its own. */
    private static final String TEXT_FIELD = "Text";

    /** Title of the block naming the attachments an edit changed, which no section of the body covers. */
    private static final String FILES_SECTION = "Files";

    /**
     * Fields not worth a line in the comment. The catalog link carries the revision number, so it
     * changes on every edit while pointing at the same submission - noise in a list whose whole
     * point is that everything in it matters.
     */
    private static final Set<String> IGNORED = Set.of("Open in the catalog");

    /**
     * Fields worth listing but not worth a comment of their own. An edit bumps the revision number
     * whether or not it changed anything else, so a save that touched nothing would otherwise still
     * announce itself; the number is reported when something did change, and is silent when nothing did.
     */
    private static final Set<String> INCIDENTAL = Set.of("Revision");

    /** Values are quoted in full up to this; a tutorial-sized description would drown the list. */
    private static final int MAX_VALUE = 300;

    /**
     * The changes between two descriptions of the same submission, as markdown.
     */
    public Optional<String> compare(String before, String after, String lead) {
        return compare(before, after, lead, List.of());
    }

    /**
     * The same, plus file changes, which the descriptions cannot show: the tutorial is attached rather
     * than written into the body, so only the caller that replaced the files knows.
     */
    public Optional<String> compare(String before, String after, String lead, List<String> fileChanges) {
        List<String> blocks = new ArrayList<>();
        boolean worthSaying = false;

        if (before != null && !before.isBlank() && after != null && !after.isBlank()) {
            Map<String, Map<String, String>> was = parse(before);
            Map<String, Map<String, String>> now = parse(after);

            for (Map.Entry<String, Map<String, String>> section : now.entrySet()) {
                Map<String, String> previous = was.get(section.getKey());
                if (previous == null) {
                    blocks.add(block(section.getKey(), List.of("* Added to the submission.")));
                    worthSaying = true;
                    continue;
                }
                Changes changes = changedFields(previous, section.getValue());
                if (!changes.lines().isEmpty()) {
                    blocks.add(block(section.getKey(), changes.lines()));
                    worthSaying |= changes.worthSaying();
                }
            }
            for (String section : was.keySet()) {
                if (!now.containsKey(section)) {
                    blocks.add(block(section, List.of("* No longer part of the submission.")));
                    worthSaying = true;
                }
            }
        }

        if (!fileChanges.isEmpty()) {
            blocks.add(block(FILES_SECTION, fileChanges.stream().map(change -> "* " + change).toList()));
            worthSaying = true;
        }

        if (!worthSaying) {
            return Optional.empty();
        }
        return Optional.of(lead + "\n\n" + String.join("\n", blocks));
    }

    /**
     * One section's differences, and whether any of them is a reason to say anything at all - see
     * {@link #INCIDENTAL}.
     */
    private record Changes(List<String> lines, boolean worthSaying) {
    }

    /** One section's changes under its own title, which the untitled opening block does without. */
    private static String block(String section, List<String> changes) {
        StringBuilder rendered = new StringBuilder();
        if (!section.isBlank()) {
            rendered.append("**").append(section).append("**\n\n");
        }
        for (String change : changes) {
            rendered.append(change).append('\n');
        }
        return rendered.toString();
    }

    /**
     * The fields of one section that differ, in the order the edited description lists them, with
     * fields that only the earlier version had after them.
     */
    private static Changes changedFields(Map<String, String> was, Map<String, String> now) {
        Set<String> labels = new LinkedHashSet<>(now.keySet());
        labels.addAll(was.keySet());

        List<String> changes = new ArrayList<>();
        boolean worthSaying = false;
        for (String label : labels) {
            String previous = was.get(label);
            String current = now.get(label);
            if (Objects.equals(previous, current)) {
                continue;
            }
            worthSaying |= !INCIDENTAL.contains(label);
            if (previous == null) {
                changes.add("* **" + label + ":** added - " + shown(current));
            } else if (current == null) {
                changes.add("* **" + label + ":** removed, was " + shown(previous));
            } else {
                changes.add("* **" + label + ":** " + shown(previous) + " → " + shown(current));
            }
        }
        return new Changes(changes, worthSaying);
    }

    /** A value as it goes into the comment: never empty, never long enough to bury the next line. */
    private static String shown(String value) {
        if (value == null || value.isBlank()) {
            return "_empty_";
        }
        return value.length() <= MAX_VALUE ? value : value.substring(0, MAX_VALUE) + "…";
    }

    /**
     * A description read as sections of labelled fields. Everything that is neither a heading nor a
     * field is kept as the section's prose, so a change to a paragraph - the method's own
     * description, or the note saying whether a tutorial is attached - is not silently dropped.
     */
    private Map<String, Map<String, String>> parse(String body) {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        Map<String, String> current = openSection(sections, "");
        String parent = null;

        for (String line : body.split("\\R")) {
            Matcher bullet = BULLET.matcher(line);
            if (bullet.matches()) {
                boolean nested = !bullet.group(1).isEmpty();
                String label = bullet.group(2);
                String value = bullet.group(3);
                if (nested) {
                    label = parent == null ? label : parent + " / " + label;
                } else if (value.isBlank()) {
                    parent = label;
                    continue;
                } else {
                    parent = null;
                }
                if (!IGNORED.contains(label)) {
                    current.put(label, value);
                }
                continue;
            }

            Matcher heading = HEADING.matcher(line);
            Matcher bold = BOLD_LINE.matcher(line);
            if (heading.matches() || bold.matches()) {
                current = openSection(sections, heading.matches() ? heading.group(1) : bold.group(1));
                parent = null;
                continue;
            }

            String prose = line.trim();
            if (!prose.isEmpty() && !SupportTicketDescriptionBuilder.NOTES.contains(prose)) {
                current.merge(TEXT_FIELD, prose, (kept, added) -> kept + " " + added);
            }
        }
        return sections;
    }

    /**
     * Starts a section, keeping only a connector's own name as the title so that a change in its
     * review status reads as a changed field rather than as one connector removed and another added.
     * A repeated title is numbered, since sections may legitimately share one.
     */
    private static Map<String, String> openSection(Map<String, Map<String, String>> sections, String title) {
        String name = title;
        String status = null;
        Matcher connector = CONNECTOR_HEADING.matcher(title);
        if (connector.matches()) {
            name = connector.group(1);
            status = connector.group(2);
        }

        String key = name;
        for (int repeat = 2; sections.containsKey(key); repeat++) {
            key = name + " (" + repeat + ")";
        }

        Map<String, String> fields = new LinkedHashMap<>();
        if (status != null) {
            fields.put(STATUS_FIELD, status);
        }
        sections.put(key, fields);
        return fields;
    }
}
