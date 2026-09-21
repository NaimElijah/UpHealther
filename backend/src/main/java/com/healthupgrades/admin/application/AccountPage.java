package com.healthupgrades.admin.application;

import com.healthupgrades.user.domain.model.User;

import java.util.List;

/**
 * One page of accounts, with the total behind it.
 *
 * <p>The total is here because a page without one cannot be paged through: a caller shown ten accounts
 * has no way to know whether there are four more or four thousand, and "fetch until a short page comes
 * back" is a loop that reads the whole table to discover it is finished.
 *
 * @param accounts the accounts on this page, oldest first
 * @param page     the zero-based page number this is
 * @param size     the page size that was asked for
 * @param total    how many accounts exist in all
 */
public record AccountPage(List<User> accounts, int page, int size, long total) {
}
