/**
 * Accounts: the {@code User} aggregate, {@code EmailAddress}, the one rule for when two email addresses are the
 * same login, and the ports the other contexts read and write accounts through.
 *
 * <p>The context has no web adapter. {@code auth} registers accounts and signs them in, and {@code admin} lists
 * and changes them, both through {@code UserQuery} and {@code UserCommand}. The listing of every account is on
 * {@code AccountListQuery}, a port of its own that only {@code AdminUserService} may use, and the
 * package-private {@code AccountListing} implements it.
 *
 * <p>Rules a maintainer must keep:
 *
 * <ul>
 *   <li><strong>{@code User} has no setters.</strong> An account is created through {@code User.register},
 *       which leaves the role at {@code USER} and the account enabled, and changes afterwards only through
 *       {@code changeRole}, {@code disable} and {@code enable}.
 *   <li><strong>Emails are stored trimmed and lowercased.</strong> Callers normalise with
 *       {@code EmailAddress.normalise}, the lookups compare exactly, and the database refuses any other form
 *       (FR-4). {@code saveAndFlush} turns a duplicate address into {@code EmailAlreadyRegisteredException}.
 *   <li><strong>A write that was overtaken is refused.</strong> {@code User} carries {@code @Version}, and
 *       {@code save} flushes so the clash is raised inside the call as
 *       {@code AccountModifiedConcurrentlyException}, a 409 and an audited refusal (NFR-34, ADR-073).
 *   <li><strong>The role is read from the database on every request</strong> and is in no token (ADR-016).
 *   <li><strong>The shared kernel never names this context.</strong> {@code AccountCredentialsAdapter} answers
 *       {@code common}'s {@code AccountCredentialsPort} (ADR-062).
 * </ul>
 */
package com.healthupgrades.user;
