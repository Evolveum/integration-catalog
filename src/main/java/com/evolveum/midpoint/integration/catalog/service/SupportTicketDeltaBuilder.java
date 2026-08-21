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
 * Says what an edit changed about a submission, as a comment for its support work package.
 *
 * <p>A reviewer who has already read the ticket should not have to read all of it again to find the
 * one line the author corrected, which is what a rewritten description alone asks of them. This
 * turns two versions of that description into the short list of fields that actually differ.
 *
 * <p>The comparison is made on the rendered description rather than on the entities behind it, for
 * two reasons. The description is the only form of the submission that survives an in-place edit -
 * the row it was built from is deleted and replaced by the edit itself, so the portal's copy is all
 * that is left of "before". And it is by definition exactly what the reviewer read, so a field this
 * reports as changed is a line they can see changing; a comparison of entities could report
 * something the ticket never showed, or miss something it did.
 *
 * <p>The description's own shape carries the structure: headings open a section, {@code * **Label:**
 * value} bullets are its fields, and anything else is the section's prose. Nothing here needs to
 * know which fields {@link SupportTicketDescriptionBuilder} writes, so a field added there is
 * compared without a change here.
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
     *
     * @param before   the description as the reviewer last saw it
     * @param after    the description as it stands after the edit
     * @param lead     opening sentence, which says what the two versions are - the caller knows
     *                 whether the earlier one is this ticket's own text or another revision's
     * @return the comment, or empty when the two say the same thing and there is nothing to report.
     * A blank version yields empty as well: everything would be listed as added, which is what the
     * description itself already says.
     */
    public Optional<String> compare(String before, String after, String lead) {
        return compare(before, after, lead, List.of());
    }

    /**
     * The same, with what changed among the submission's files.
     *
     * <p>Those cannot be read out of the descriptions: the tutorial is attached rather than written
     * into the body, so an author who rewrites it changes nothing the two versions show. The caller
     * that replaces the files knows what it replaced, and says so here.
     *
     * @param fileChanges one phrase per file, already in a reader's terms
     */
    public Optional<String> compare(String before, String after, String lead, List<String> fileChanges) {
        List<String> blocks = new ArrayList<>();
        boolean worthSaying = false;

        // A missing earlier version is not an error: a work package that was opened with no description
        // has nothing to compare, and listing the whole submission as added would only repeat it.
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
        // Whatever stands before the first heading belongs to a section with no title of its own.
        Map<String, String> current = openSection(sections, "");
        String parent = null;

        for (String line : body.split("\\R")) {
            Matcher bullet = BULLET.matcher(line);
            if (bullet.matches()) {
                boolean nested = !bullet.group(1).isEmpty();
                String label = bullet.group(2);
                String value = bullet.group(3);
                if (nested) {
                    // Read on its own, "Global" says nothing; it is the bullet above that gives it
                    // its meaning, and that bullet is where the reviewer's eye goes.
                    label = parent == null ? label : parent + " / " + label;
                } else if (value.isBlank()) {
                    // A bullet with no value of its own is only there to head the ones below it,
                    // so it is that heading rather than a field that could differ.
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
     * Starts a section, keeping a connector's own name as its title: the rest of that heading says
     * whether this submission publishes the connector, which is a fact about it that can change
     * (editing a published connector puts it up for review) and is compared as a field like any
     * other. Were it left in the title, that one change would read as a whole connector removed and
     * another added.
     *
     * <p>Two sections can legitimately share a title - every connector titles its bundle block the
     * same way - so a repeated one is numbered rather than merged into the first.
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
