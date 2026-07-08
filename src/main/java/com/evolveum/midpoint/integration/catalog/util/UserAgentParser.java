/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.util;

public class UserAgentParser {

    public static String parseBrowserName(String userAgent) {
        if (userAgent == null || userAgent.isEmpty()) {
            return "Unknown";
        }

        String ua = userAgent.toLowerCase();

        if (ua.contains("edg/") || ua.contains("edge/")) {
            return "Edge";
        } else if (ua.contains("opr/") || ua.contains("opera")) {
            return "Opera";
        } else if (ua.contains("chrome/") && !ua.contains("chromium")) {
            return "Chrome";
        } else if (ua.contains("safari/") && !ua.contains("chrome") && !ua.contains("chromium")) {
            return "Safari";
        } else if (ua.contains("firefox/")) {
            return "Firefox";
        } else if (ua.contains("msie") || ua.contains("trident/")) {
            return "Internet Explorer";
        } else {
            return "Other";
        }
    }

    public static String parseDeviceType(String userAgent) {
        if (userAgent == null || userAgent.isEmpty()) {
            return "Unknown";
        }

        String ua = userAgent.toLowerCase();

        if (ua.contains("mobile") || ua.contains("android") && !ua.contains("tablet")) {
            if (ua.contains("tablet") || ua.contains("ipad")) {
                return "Tablet";
            }
            return "Mobile";
        } else if (ua.contains("tablet") || ua.contains("ipad")) {
            return "Tablet";
        } else {
            return "Desktop";
        }
    }
}
