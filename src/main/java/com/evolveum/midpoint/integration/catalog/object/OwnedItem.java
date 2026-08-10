/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

/**
 * A catalog item that records who owns it: the connector, its bundle, their versions and
 * the integration method. Ownership is stamped when the item is written, because a token
 * describes only its own bearer and the application has no user directory to ask later.
 * <p>
 * The setters are declared as returning {@link Object} so that the entities' chained Lombok
 * setters (which return the entity) implement them by covariant return.
 */
public interface OwnedItem {

    /** Username of the user who uploaded this item. */
    String getAuthor();

    Object setAuthor(String author);

    /** Identifier of the organization the author uploaded on behalf of, if any. */
    String getAuthorOrgId();

    Object setAuthorOrgId(String authorOrgId);

    /** The author's catalog category at the time of writing (Evolveum/Partner/Community). */
    String getAuthorCategory();

    Object setAuthorCategory(String authorCategory);

    /** Username of the designated maintainer; null when an organization maintains the item. */
    String getMaintainer();

    Object setMaintainer(String maintainer);

    /** Identifier of the maintaining organization, or of the maintainer's organization. */
    String getMaintainerOrgId();

    Object setMaintainerOrgId(String maintainerOrgId);
}
