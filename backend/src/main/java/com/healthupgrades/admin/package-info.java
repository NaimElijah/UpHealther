/**
 * Account administration: listing accounts, switching one off or back on, granting or revoking a role, and
 * promoting the first administrator at startup.
 *
 * <p>The context owns no aggregate. It orchestrates over the {@code user} context (accounts) and the
 * {@code auth} context (sessions) and, by {@code HexagonalArchitectureTest}, may reach nothing else but
 * {@code common}: an administrator gains paths, not rows, and cannot read a user's own records (ADR-016).
 *
 * <p>Rules a maintainer must keep:
 *
 * <ul>
 *   <li><strong>An administrator may not act on their own account.</strong> {@code AdminUserService} refuses it
 *       with a {@code BusinessRuleException} (422), so every command takes the acting administrator's id as
 *       well as the target's.
 *   <li><strong>Disabling ends every session the account holds</strong>, inside the transaction that sets the
 *       flag (FR-43). A role change leaves sessions alone, because the role is read from the database on
 *       every request.
 *   <li><strong>Every action is audited</strong>, the account list included, because it is the one read
 *       across accounts ({@code ADMIN_LIST}, NFR-23). {@code AccountListQuery} may be used only by
 *       {@code AdminUserService} and its implementer, so the audit entry cannot be skipped.
 *   <li><strong>The path is closed twice.</strong> {@code @PreAuthorize("hasRole('ADMIN')")} on the
 *       controller, and the {@code /api/admin/**} matcher in {@code SecurityConfig}, which catches a path
 *       someone adds and forgets to annotate.
 *   <li><strong>The bootstrap is a service of its own.</strong> {@code AdminBootstrapService} promotes an
 *       account to ADMIN with no administrator asking, so only {@code AdminBootstrapRunner} may use it. It
 *       names the account by id, never by email, and acts only while no enabled administrator exists. A
 *       misconfiguration is logged and does not stop startup.
 * </ul>
 *
 * <p>Configuration: {@code app.admin.bootstrap-user-id}.
 */
package com.healthupgrades.admin;
