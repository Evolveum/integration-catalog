/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */
package com.evolveum.midpoint.integration.catalog.object;

import java.util.List;

public interface GetOwnershipListMaintainer {
    Author getAuthor();

    List<Maintainer> getMaintainer();
}
