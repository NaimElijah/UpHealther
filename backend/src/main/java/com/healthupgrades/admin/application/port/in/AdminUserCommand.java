package com.healthupgrades.admin.application.port.in;

import com.healthupgrades.user.domain.model.Role;
import com.healthupgrades.user.domain.model.User;

import java.util.UUID;

/**
 * Inbound write port for account administration: switching an account off, back on, and changing what
 * it may do.
 *
 * <p>Every method takes the administrator's own id as well as the target's. It is not decoration: an
 * administrator may not act on their own account, and enforcing that needs to know who is asking. The
 * rule exists because the alternatives are worse — an administrator who disables themselves locks the
 * installation's last way in, and one who demotes themselves cannot undo it.
 */
public interface AdminUserCommand {

    /**
     * Switches an account off. It can no longer sign in, and every session it holds is ended, so a
     * token already issued stops working on its next request rather than at expiry.
     *
     * @param actingAdminId the administrator making the change
     * @param targetUserId  the account to disable
     * @return the account as it now stands
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if no such account
     * @throws com.healthupgrades.common.domain.exception.BusinessRuleException if an administrator aims
     *         at their own account
     */
    User disable(UUID actingAdminId, UUID targetUserId);

    /**
     * Switches an account back on, with everything it owned still in place.
     *
     * @see #disable for the parameters and the refusals
     */
    User enable(UUID actingAdminId, UUID targetUserId);

    /**
     * Grants or revokes a role.
     *
     * @param actingAdminId the administrator making the change
     * @param targetUserId  the account whose role is changing
     * @param role          the role it is to hold
     * @return the account as it now stands
     * @see #disable for the refusals
     */
    User changeRole(UUID actingAdminId, UUID targetUserId, Role role);
}
