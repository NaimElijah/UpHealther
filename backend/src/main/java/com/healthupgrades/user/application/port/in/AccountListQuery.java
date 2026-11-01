package com.healthupgrades.user.application.port.in;

import com.healthupgrades.user.domain.model.UserPage;

/**
 * Inbound port paging through every account on the installation, for the administration context alone.
 *
 * <p>Not on {@link UserQuery}, which every context may reach: these reads cover every account, names and
 * addresses included, and nothing but the administrator's account list has a use for them (S5.B6).
 * {@code HexagonalArchitectureTest} lets only {@code AdminUserService}, which audits each listing as
 * {@code admin.list}, and the implementer depend on it.
 */
public interface AccountListQuery {

    /**
     * Reads one page of accounts, oldest first.
     *
     * <p>Takes a page number and a size rather than a {@code Pageable}: paging is a property of the
     * request, but {@code Pageable} is Spring Data's, and letting it through here would put a
     * persistence type in a contract another context depends on. The adapter turns these two numbers
     * into one.
     *
     * <p>The order is fixed rather than a parameter, because an unordered page is not a page: without
     * it the database may return the same row on two pages and never return another.
     *
     * @param page zero-based page number
     * @param size how many accounts per page
     * @return that page of accounts, oldest first, and how many accounts exist in all
     */
    UserPage findAll(int page, int size);
}
