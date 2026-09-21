package com.healthupgrades.user.application;

import com.healthupgrades.user.application.port.in.UserCommand;
import com.healthupgrades.user.application.port.in.UserQuery;
import com.healthupgrades.user.domain.model.Role;
import com.healthupgrades.user.domain.model.User;
import com.healthupgrades.user.domain.port.out.UserRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service implementing the user inbound ports ({@link UserQuery} reads, {@link UserCommand}
 * writes) over the user repository port.
 */
@Service
@RequiredArgsConstructor
public class UserService implements UserQuery, UserCommand {

    private final UserRepositoryPort repository; // outbound persistence port

    /** {@inheritDoc} */
    @Override
    public Optional<User> findById(UUID id) {
        return repository.findById(id);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<User> findByEmail(String email) {
        return repository.findByEmail(email);
    }

    /** {@inheritDoc} */
    @Override
    public boolean existsByEmail(String email) {
        return repository.existsByEmail(email);
    }

    /** {@inheritDoc} */
    @Override
    public List<User> findAll(int page, int size) {
        return repository.findAll(page, size);
    }

    /** {@inheritDoc} */
    @Override
    public long count() {
        return repository.count();
    }

    /** {@inheritDoc} */
    @Override
    public boolean existsEnabledWithRole(Role role) {
        return repository.existsEnabledWithRole(role);
    }

    /** {@inheritDoc} */
    @Override
    public User save(User user) {
        return repository.save(user);
    }

    /** {@inheritDoc} */
    @Override
    public User register(User user) {
        return repository.saveAndFlush(user);
    }
}
