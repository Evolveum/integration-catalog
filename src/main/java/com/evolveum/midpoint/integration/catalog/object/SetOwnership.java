/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

//TODO change javadoc
/**
 * A catalog item that records who owns it: the connector, its bundle, their versions and
 * the integration method. Ownership is stamped when the item is written, because a token
 * describes only its own bearer and the application has no user directory to ask later.
 */
public interface SetOwnership {
    Object setAuthor(Author author);

    Object setMaintainer(Maintainer maintainer);
}
