/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

/**
 * A catalog item whose ownership can be written: the connector, its bundle, their versions and
 * the integration method. Implemented alongside one of the two reading interfaces - an item with a
 * single maintainer implements {@link GetOwnershipOneMaintainer}, one that can have several
 * {@link GetOwnershipListMaintainer} - which is why writing is an interface of its own.
 */
public interface SetOwnership {
    Object setAuthor(Author author);

    Object setMaintainer(Maintainer maintainer);
}
