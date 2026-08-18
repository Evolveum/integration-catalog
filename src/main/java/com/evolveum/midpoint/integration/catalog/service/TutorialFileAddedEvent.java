/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import java.util.UUID;

/**
 * Raised when a tutorial or sample file is stored for a revision.
 *
 * <p>Exists because the publish form uploads these files <em>after</em> the submission itself: the
 * create call carries no files, and the frontend posts each one to its own multipart endpoint once
 * that call has returned. The work package is opened when the create call commits, so its description
 * is written while the folder is still empty and lists no files - which is why every file announces
 * itself here instead.
 *
 * @param methodId identifies the integration method
 * @param revision identifies the revision the file was stored for
 * @param fileName the stored file's name, which is not necessarily the uploaded one - a clashing name
 *                 is made unique on the way in
 */
public record TutorialFileAddedEvent(UUID methodId, String revision, String fileName) {
}
