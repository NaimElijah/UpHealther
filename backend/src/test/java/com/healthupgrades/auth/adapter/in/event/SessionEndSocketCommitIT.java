package com.healthupgrades.auth.adapter.in.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.healthupgrades.auth.application.SessionGrant;
import com.healthupgrades.auth.application.port.in.SessionCommand;
import com.healthupgrades.common.domain.port.out.LiveSocketPort;
import com.healthupgrades.support.AUser;
import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.user.application.port.in.UserCommand;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-054 against a real transaction manager: a session's sockets are closed once its end has committed,
 * never while it is still in flight, and not at all when it rolls back.
 *
 * <p>Closing inside the transaction would let a socket reconnect while the end was still uncommitted, read
 * the session as live, and stay open for good. {@code StompSessionEndIT} cannot see the difference, since
 * either way the socket ends up closed, and {@code AuthSessionServiceTest} publishes into a list with no
 * transaction at all. So the ends are run inside a {@link TransactionTemplate} here, the port is asked
 * from inside it, and the rollback is forced with {@code setRollbackOnly()} so that it is deterministic.
 *
 * <p>The port is a mock, of our own outbound port, so what is observed is when it is asked rather than
 * what a socket does. Each test signs a fresh account in, because the integration tests share one
 * database.
 */
@SpringBootTest
class SessionEndSocketCommitIT extends PostgresIT {

    @Autowired
    SessionCommand sessions;

    @Autowired
    UserCommand userCommand;

    @Autowired
    TransactionTemplate transactionTemplate;

    @MockitoBean
    LiveSocketPort sockets;

    private UUID userId;
    private SessionGrant signedIn;

    @BeforeEach
    void aSignedInAccount() {
        userId = userCommand
                .save(AUser.aUser()
                        .id(null)
                        .email(UUID.randomUUID() + "@example.com")
                        .build())
                .getId();
        signedIn = sessions.open(userId);
    }

    @Test
    void GivenASignOutInFlight_WhenItCommits_ThenItsSocketsCloseThenAndNotBefore() {
        transactionTemplate.executeWithoutResult(status -> {
            sessions.revoke(signedIn.refreshToken());
            verify(sockets, never()).closeForSession(any());
        });

        verify(sockets).closeForSession(signedIn.sessionId());
    }

    @Test
    void GivenASignOutThatRollsBack_WhenItEnds_ThenNoSocketIsClosed() {
        transactionTemplate.executeWithoutResult(status -> {
            sessions.revoke(signedIn.refreshToken());
            status.setRollbackOnly();
        });

        verify(sockets, never()).closeForSession(any());
    }

    @Test
    void GivenAnAccountsSessionsEndingInFlight_WhenTheEndCommits_ThenItsSocketsCloseThenAndNotBefore() {
        // What switching an account off does: AdminUserService.disable revokes inside its own transaction.
        transactionTemplate.executeWithoutResult(status -> {
            sessions.revokeAllForUser(userId);
            verify(sockets, never()).closeForUser(any());
        });

        verify(sockets).closeForUser(userId);
    }

    @Test
    void GivenAnAccountsSessionsEndingInATransactionThatRollsBack_WhenItEnds_ThenNoSocketIsClosed() {
        // The switch-off whose audit write failed: nothing was switched off, so nothing may be closed.
        transactionTemplate.executeWithoutResult(status -> {
            sessions.revokeAllForUser(userId);
            status.setRollbackOnly();
        });

        verify(sockets, never()).closeForUser(any());
    }
}
