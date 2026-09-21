package com.healthupgrades.user.domain.model;

/**
 * What an account may do beyond owning its own records.
 *
 * <p>Two values and no hierarchy, deliberately. Authorisation here is almost entirely ownership — every
 * query is scoped by user id, so one account cannot read another's rows whatever its role — and the
 * only thing a role decides is whether the account-administration endpoints answer. A third role would
 * need a reason recorded in an ADR, not a value added here.
 *
 * <p>The role is read from the database on every request rather than carried in the access token, so
 * revoking ADMIN takes effect on the next request instead of whenever the token happens to expire.
 */
public enum Role {

    /** An ordinary account: it owns its own records and can reach nothing else. */
    USER,

    /**
     * May list accounts, disable and re-enable them, and grant or revoke ADMIN. An administrator reads
     * no health data: the ownership scoping applies to them exactly as it does to everyone else.
     */
    ADMIN
}
