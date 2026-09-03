/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import java.util.UUID;

/**
 * Raised when a tutorial or sample file is stored, because these are uploaded after the submission
 * itself and so are missing from the work package description written at that point.
 */
public record TutorialFileAddedEvent(UUID methodId, String revision, String fileName) {
}
