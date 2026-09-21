package com.healthupgrades.admin.application.port.in;

import com.healthupgrades.admin.application.AccountPage;

/**
 * Inbound read port for account administration.
 *
 * <p>Deliberately narrow: an administrator may enumerate accounts and nothing else. There is no
 * "look at this user's upgrades" here, and adding one would not be a feature — it would undo the
 * property the whole persistence layer is built to give, that nobody reads another person's health
 * data (ADR-016).
 */
public interface AdminUserQuery {

    /**
     * Reads one page of accounts, oldest first.
     *
     * @param page zero-based page number
     * @param size how many accounts per page
     * @return that page, with the total so a caller knows how many more there are
     */
    AccountPage list(int page, int size);
}
