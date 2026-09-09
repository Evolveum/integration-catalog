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
 */
public interface OwnedItem {

    String getAuthor();

    Object setAuthor(String author);

    Integer getAuthorOrgId();

    Object setAuthorOrgId(Integer authorOrgId);

    String getAuthorCategory();

    Object setAuthorCategory(String authorCategory);

    String getAuthorEmail();

    Object setAuthorEmail(String authorEmail);

    String getMaintainer();

    Object setMaintainer(String maintainer);

    Integer getMaintainerOrgId();

    Object setMaintainerOrgId(Integer maintainerOrgId);
}
