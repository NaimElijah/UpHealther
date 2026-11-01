package com.healthupgrades.user.application;

import com.healthupgrades.user.application.port.in.AccountListQuery;
import com.healthupgrades.user.domain.model.UserPage;
import com.healthupgrades.user.domain.port.out.UserRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The account listing, {@link AccountListQuery} over the repository.
 *
 * <p>Package-private and apart from {@link UserService}, for the reason {@code TrackingConfigReads} gives:
 * a public class implementing the port would be a way to reach the read past the rule on the port.
 */
@Service
@RequiredArgsConstructor
class AccountListing implements AccountListQuery {

    private final UserRepositoryPort repository;

    /** {@inheritDoc} */
    @Override
    public UserPage findAll(int page, int size) {
        return repository.findAll(page, size);
    }
}
