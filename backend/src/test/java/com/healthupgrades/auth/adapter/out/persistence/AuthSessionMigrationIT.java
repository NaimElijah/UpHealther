package com.healthupgrades.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.support.PostgresIT;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * What {@code V10__auth_session_spent_tokens.sql} does to the sessions it finds (ADR-065).
 *
 * <p>Every other IT migrates an empty database straight to the latest version, so a migration's effect
 * on rows written before it runs is seen by none of them. This one migrates a database of its own to V9,
 * writes a session as V9 stored one, and then runs V10 over it.
 *
 * <p>V10 renames nothing in the database, but it ships with the cookie's rename to
 * {@code __Host-refresh_token}: from then on no browser presents the credential it holds, and a session
 * left live would be out of reach of reuse detection, its owner signing in afresh and never presenting
 * the old credential. So V10 revokes every session still live.
 */
class AuthSessionMigrationIT extends PostgresIT {

    /** The account {@code V2__seed_data.sql} creates, which a V9 session must belong to. */
    private static final UUID SEEDED_ACCOUNT = UUID.fromString("a0000000-0000-0000-0000-000000000001");

    @Test
    void GivenALiveSessionStoredUnderV9_WhenV10Runs_ThenTheSessionIsRevoked() throws SQLException {
        IsolatedDatabase database = createIsolatedDatabase("v10_revokes_live_sessions");
        migrate(database, "9");
        UUID live = UUID.randomUUID();
        try (Connection connection = database.connect()) {
            insertLiveV9Session(connection, live);
        }

        migrate(database, "10");

        try (Connection connection = database.connect()) {
            assertThat(isRevoked(connection, live))
                    .as("no credential of this session can be presented after the rename, so none may stay live")
                    .isTrue();
        }
    }

    private static void migrate(IsolatedDatabase database, String version) {
        Flyway.configure()
                .dataSource(database.jdbcUrl(), database.username(), database.password())
                .target(version)
                .load()
                .migrate();
    }

    /** A session as V9 stored one: rotated once, so its previous digest is set, and nowhere near expiry. */
    private static void insertLiveV9Session(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO auth_sessions (id, user_id, refresh_token_hash, previous_token_hash, created_at,
                    last_used_at, rotated_at, idle_expires_at, absolute_expires_at, revoked)
                VALUES (?, ?, ?, ?, now(), now(), now(), now() + interval '7 days',
                    now() + interval '30 days', FALSE)
                """)) {
            insert.setObject(1, id);
            insert.setObject(2, SEEDED_ACCOUNT);
            insert.setBytes(3, new byte[] {1, 2, 3});
            insert.setBytes(4, new byte[] {4, 5, 6});
            insert.executeUpdate();
        }
    }

    private static boolean isRevoked(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT revoked FROM auth_sessions WHERE id = ?")) {
            select.setObject(1, id);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next())
                        .as("the session row survives the migration")
                        .isTrue();
                return row.getBoolean("revoked");
            }
        }
    }
}
