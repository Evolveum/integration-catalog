/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

public enum LifecycleType {
    IN_REVIEW,
    // A superuser has started reviewing an IN_REVIEW revision. The revision is locked for editing
    // until the review is resolved (approve -> ACTIVE, reject -> REJECTED, or stop -> IN_REVIEW).
    REVIEWING,
    ACTIVE,
    DEPRECATED,
    ARCHIVED,
    WITH_ERROR,
    REJECTED
}
