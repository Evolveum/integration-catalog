/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

/**
 * Projection of an owned item's two owner columns, so the owner queries fetch names instead
 * of whole entity graphs.
 */
public interface ItemOwnerView {

    String getAuthor();

    String getMaintainer();
}
