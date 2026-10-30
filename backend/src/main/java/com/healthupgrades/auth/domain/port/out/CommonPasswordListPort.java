package com.healthupgrades.auth.domain.port.out;

import java.util.List;

/**
 * Outbound port: the entries of the common-password list that registration refuses (BR-17, ADR-071).
 *
 * <p>Implemented by {@code ClasspathCommonPasswordList}, which reads the list bundled in the jar. The
 * caller normalises what it is given, so an implementation returns the entries as stored.
 */
public interface CommonPasswordListPort {

    /**
     * Reads the list.
     *
     * @return every entry, in the list's order; never empty, and never a blank entry
     * @throws IllegalStateException if the list is missing or holds no entries but blank ones, which is a
     *         broken build rather than a state to run in
     */
    List<String> entries();
}
