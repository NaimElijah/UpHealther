package com.healthupgrades.user.adapter.in.composition;

import com.healthupgrades.common.domain.port.out.AccountCredentials;
import com.healthupgrades.common.domain.port.out.AccountCredentialsPort;
import com.healthupgrades.user.application.port.in.UserQuery;
import com.healthupgrades.user.domain.model.EmailAddress;
import com.healthupgrades.user.domain.model.User;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Driving adapter that answers the shared kernel's {@link AccountCredentialsPort} from this context's own
 * {@link UserQuery}.
 *
 * <p>The supplying half of an inverted dependency. The security adapter in {@code common} has to load an
 * account to authenticate as it; this context is what holds accounts. Declaring the contract there and
 * implementing it here leaves a single arrow — from {@code user} to {@code common} — where a direct call
 * had pointed the shared kernel at a bounded context (ADR-062).
 *
 * <p>The address is normalised here, not in the kernel, because {@link EmailAddress} is this context's
 * rule for when two addresses are one account. Every sign-in reaches the lookup through this method, so
 * none can skip it.
 */
@Component
@RequiredArgsConstructor
public class AccountCredentialsAdapter implements AccountCredentialsPort {

    private final UserQuery userQuery;

    /** {@inheritDoc} */
    @Override
    public Optional<AccountCredentials> findById(UUID accountId) {
        return userQuery.findById(accountId).map(AccountCredentialsAdapter::credentialsOf);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AccountCredentials> findByEmail(String email) {
        return userQuery.findByEmail(EmailAddress.normalise(email)).map(AccountCredentialsAdapter::credentialsOf);
    }

    /** The role travels by name, the one form of it the kernel's principal needs. */
    private static AccountCredentials credentialsOf(User user) {
        return new AccountCredentials(
                user.getId(),
                user.getEmail(),
                user.getPasswordHash(),
                user.getRole().name(),
                user.isEnabled());
    }
}
