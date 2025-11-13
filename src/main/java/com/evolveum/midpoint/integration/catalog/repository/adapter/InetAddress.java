/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository.adapter;

import lombok.Getter;

import java.io.Serializable;
import java.net.Inet4Address;
import java.net.UnknownHostException;

@Getter
public class InetAddress implements Serializable {

    private final String address;

    public InetAddress(String address) {
        this.address = address;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;

        if (o == null || getClass() != o.getClass())
            return false;

        InetAddress inetAddress = (InetAddress) o;

        return address != null ? address.equals(inetAddress.address) : inetAddress.address == null;
    }

    @Override
    public int hashCode() {
        return address != null ?
                address.hashCode() :
                0;
    }

    public java.net.InetAddress toInetAddress() {
        try {
            String host = address.replaceAll(
                    "\\/.*$", ""
            );

            return Inet4Address.getByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
