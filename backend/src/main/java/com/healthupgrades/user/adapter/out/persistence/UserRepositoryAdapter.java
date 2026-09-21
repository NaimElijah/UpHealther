package com.healthupgrades.user.adapter.out.persistence;

import com.healthupgrades.user.domain.model.EmailAlreadyRegisteredException;
import com.healthupgrades.user.domain.model.Role;
import com.healthupgrades.user.domain.model.User; // domain aggregate
import com.healthupgrades.user.domain.port.out.UserRepositoryPort; // the port implemented here
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapter implementing {@link UserRepositoryPort} by delegating to Spring Data JPA.
 *
 * <p>The adapter exists so the Spring Data interface can stay package-private: the port is the only
 * thing the rest of the application sees, which is what keeps JPA confined to this package.
 */
@Component
@RequiredArgsConstructor
class UserRepositoryAdapter implements UserRepositoryPort {

    /** PostgreSQL's name for {@code email ... UNIQUE} in {@code V1__init_schema.sql}. */
    static final String EMAIL_UNIQUE_CONSTRAINT = "users_email_key";

    private final UserJpaRepository jpa; // Spring Data proxy

    /** {@inheritDoc} */
    @Override
    public User save(User user) {
        return jpa.save(user);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Only the email's unique constraint is translated. Any other integrity failure is a defect, not a
     * duplicate, and is rethrown unchanged so it is not reported to a user as "already registered".
     */
    @Override
    public User saveAndFlush(User user) {
        try {
            return jpa.saveAndFlush(user);
        } catch (DataIntegrityViolationException violation) {
            if (violation.getCause() instanceof ConstraintViolationException constraint
                    && EMAIL_UNIQUE_CONSTRAINT.equals(constraint.getConstraintName())) {
                throw new EmailAlreadyRegisteredException();
            }
            throw violation;
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<User> findById(UUID id) {
        return jpa.findById(id);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<User> findByEmail(String email) {
        return jpa.findByEmail(email);
    }

    /** {@inheritDoc} */
    @Override
    public boolean existsByEmail(String email) {
        return jpa.existsByEmail(email);
    }

    /**
     * {@inheritDoc}
     *
     * <p>This is where {@code Pageable} lives and stops. The sort is applied here rather than left to
     * the caller: {@code created_at} is not unique, so it is paired with the primary key to give a
     * total order — without a tie-break, two accounts created in the same millisecond can shuffle
     * between pages and one of them is never shown.
     */
    @Override
    public List<User> findAll(int page, int size) {
        return jpa.findAll(PageRequest.of(page, size, Sort.by("createdAt").and(Sort.by("id"))))
                .getContent();
    }

    /** {@inheritDoc} */
    @Override
    public long count() {
        return jpa.count();
    }

    /** {@inheritDoc} */
    @Override
    public boolean existsEnabledWithRole(Role role) {
        return jpa.existsByRoleAndEnabledTrue(role);
    }
}
