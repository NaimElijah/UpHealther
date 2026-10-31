package com.healthupgrades.user.domain.model;

import java.util.List;

/**
 * One page of accounts, read together with how many accounts there are in all.
 *
 * <p>One result rather than a page read and a count beside it, so the total is counted once, by the read
 * that needs it, and not again by the caller.
 *
 * @param users the accounts on this page, oldest first
 * @param total how many accounts exist in all
 */
public record UserPage(List<User> users, long total) {

    /** Copies the accounts, so the page cannot change after it was read. */
    public UserPage {
        users = List.copyOf(users);
    }
}
