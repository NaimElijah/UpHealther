package com.healthupgrades;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.healthupgrades.support.PostgresIT;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The value domains the schema holds of its own (ADR-073): a write that bypasses the application is refused
 * by PostgreSQL when it stores a value the domain has no name for.
 *
 * <p>Each case writes one row the way a stray {@code UPDATE} would, past every check in Java, and expects
 * the constraint that refuses it by name. Each test rolls back, so the rows it writes are its own.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ValueDomainConstraintIT extends PostgresIT {

    @Autowired
    JdbcTemplate jdbc;

    private final UUID userId = UUID.randomUUID();
    private final UUID upgradeId = UUID.randomUUID();

    @BeforeEach
    void anOwnerWithAnUpgradeAndItsRecords() {
        jdbc.update(
                "INSERT INTO users (id, name, email, password_hash) VALUES (?, 'Owner', ?, 'x')",
                userId,
                "value-domains-" + userId + "@example.com");
        jdbc.update(
                "INSERT INTO health_upgrades (id, user_id, title, type) VALUES (?, ?, 'Walk', 'HABIT')",
                upgradeId,
                userId);
        jdbc.update("INSERT INTO tracking_configs (upgrade_id, tracking_type) VALUES (?, 'RATING')", upgradeId);
        jdbc.update(
                "INSERT INTO progress_entries (upgrade_id, user_id, date) VALUES (?, ?, DATE '2026-03-02')",
                upgradeId,
                userId);
        jdbc.update(
                "INSERT INTO reflections (upgrade_id, user_id, date) VALUES (?, ?, DATE '2026-03-02')",
                upgradeId,
                userId);
        jdbc.update(
                "INSERT INTO notifications (user_id, type, category, title) VALUES (?, 'REMINDER', 'REMINDER', 'Hi')",
                userId);
        jdbc.update(
                "INSERT INTO reminders (upgrade_id, reminder_time, enabled) VALUES (?, TIME '09:00', TRUE)", upgradeId);
    }

    @ParameterizedTest(name = "{0}.{1} = {2}")
    @CsvSource(
            quoteCharacter = '"',
            value = {
                "health_upgrades,  status,            'DRAFT',     health_upgrades_status_check",
                "health_upgrades,  type,              'NAP',       health_upgrades_type_check",
                "health_upgrades,  difficulty,        'EXTREME',   health_upgrades_difficulty_check",
                "tracking_configs, tracking_type,     'COLOUR',    tracking_configs_tracking_type_check",
                "tracking_configs, frequency,         'HOURLY',    tracking_configs_frequency_check",
                "notifications,    type,              'SPAM',      notifications_type_check",
                "notifications,    category,          'URGENT',    notifications_category_check",
                "progress_entries, rating,            0,           progress_entries_rating_check",
                "progress_entries, rating,            6,           progress_entries_rating_check",
                "progress_entries, numeric_value,     -0.5,        progress_entries_numeric_value_check",
                "reflections,      difficulty_rating, 0,           reflections_difficulty_rating_check",
                "reflections,      difficulty_rating, 6,           reflections_difficulty_rating_check",
                "reflections,      benefit_rating,    0,           reflections_benefit_rating_check",
                "reflections,      benefit_rating,    6,           reflections_benefit_rating_check"
            })
    void GivenAValueOutsideItsDomain_WhenItIsWrittenPastTheApplication_ThenTheNamedConstraintRefusesIt(
            String table, String column, String value, String constraint) {
        // The table, the column and the literal come from the fixed list above, never from input. The
        // single quotes are SQL's, kept because the CSV's quote character is the double one.
        String update = "UPDATE " + table + " SET " + column + " = " + value + " WHERE " + ownerColumn(table) + " = ?";

        assertThatThrownBy(() -> jdbc.update(update, owner(table)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    @ParameterizedTest(name = "reminders.{0}")
    @CsvSource({"reminder_time", "enabled"})
    void GivenAReminderColumnSetToNull_WhenItIsWrittenPastTheApplication_ThenTheDatabaseRefusesIt(String column) {
        String update = "UPDATE reminders SET " + column + " = NULL WHERE upgrade_id = ?";

        assertThatThrownBy(() -> jdbc.update(update, upgradeId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("null value in column \"" + column + "\"");
    }

    @Test
    void GivenTheEdgesOfEachDomainAndNoValueAtAll_WhenTheyAreWritten_ThenEachIsAccepted() {
        // The bounds are inclusive, and a nullable column keeps accepting NULL: a CHECK passes it.
        jdbc.update("UPDATE progress_entries SET rating = 5, numeric_value = 0 WHERE upgrade_id = ?", upgradeId);
        jdbc.update("UPDATE progress_entries SET rating = 1, numeric_value = NULL WHERE upgrade_id = ?", upgradeId);
        jdbc.update("UPDATE reflections SET difficulty_rating = 1, benefit_rating = 5 WHERE upgrade_id = ?", upgradeId);
        jdbc.update("UPDATE reflections SET difficulty_rating = 5, benefit_rating = 1 WHERE upgrade_id = ?", upgradeId);
        jdbc.update("UPDATE health_upgrades SET difficulty = NULL, status = 'ABANDONED' WHERE id = ?", upgradeId);
        jdbc.update("UPDATE tracking_configs SET frequency = NULL WHERE upgrade_id = ?", upgradeId);

        assertThat(jdbc.queryForObject(
                        "SELECT rating FROM progress_entries WHERE upgrade_id = ?", Integer.class, upgradeId))
                .isEqualTo(1);
    }

    private static String ownerColumn(String table) {
        return switch (table) {
            case "health_upgrades" -> "id";
            case "notifications" -> "user_id";
            default -> "upgrade_id";
        };
    }

    private UUID owner(String table) {
        return table.equals("notifications") ? userId : upgradeId;
    }
}
