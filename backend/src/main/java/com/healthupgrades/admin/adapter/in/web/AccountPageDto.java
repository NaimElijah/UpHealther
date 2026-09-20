package com.healthupgrades.admin.adapter.in.web;

import java.util.List;

/**
 * One page of accounts on the wire.
 *
 * <p>`total` is not decoration: without it a client shown twenty-five accounts cannot tell whether
 * there are two more or two thousand, and would have to page until a short one came back — reading the
 * whole table to discover it had finished.
 *
 * @param accounts the accounts on this page, oldest first
 * @param page     the zero-based page number this is
 * @param size     the page size that was served
 * @param total    how many accounts exist in all
 */
public record AccountPageDto(List<AdminAccountDto> accounts, int page, int size, long total) {}
