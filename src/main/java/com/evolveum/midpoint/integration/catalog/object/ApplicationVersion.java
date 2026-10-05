/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.Comparator;

/**
 * One version of an application, kept by a superuser. Integration methods of the application state
 * the range of these they support, the way they do for midPoint versions.
 */
@Entity
@Table(name = "application_version")
@Getter @Setter
@Accessors(chain = true)
public class ApplicationVersion {

    /** Column size: application_version.version varchar(64). */
    public static final int VERSION_MAX = 64;

    /** Oldest first, comparing digit runs as numbers so that 10 sorts after 9. */
    public static final Comparator<ApplicationVersion> ORDER =
            Comparator.comparing(ApplicationVersion::getVersion, ApplicationVersion::compareVersions);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @Column(nullable = false, length = VERSION_MAX)
    private String version;

    static int compareVersions(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int endA = digitRunEnd(a, i);
                int endB = digitRunEnd(b, j);
                String runA = stripLeadingZeros(a.substring(i, endA));
                String runB = stripLeadingZeros(b.substring(j, endB));
                int cmp = runA.length() != runB.length()
                        ? Integer.compare(runA.length(), runB.length())
                        : runA.compareTo(runB);
                if (cmp != 0) {
                    return cmp;
                }
                i = endA;
                j = endB;
            } else {
                int cmp = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (cmp != 0) {
                    return cmp;
                }
                i++;
                j++;
            }
        }
        int cmp = Integer.compare(a.length() - i, b.length() - j);
        return cmp != 0 ? cmp : a.compareTo(b);
    }

    private static int digitRunEnd(String s, int from) {
        int end = from;
        while (end < s.length() && Character.isDigit(s.charAt(end))) {
            end++;
        }
        return end;
    }

    private static String stripLeadingZeros(String digits) {
        int k = 0;
        while (k < digits.length() - 1 && digits.charAt(k) == '0') {
            k++;
        }
        return digits.substring(k);
    }
}
