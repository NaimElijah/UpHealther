package com.healthupgrades.common.adapter.out.event;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.healthupgrades.common.domain.event.DomainEvent;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * NFR-6 at the one place every domain event passes: publication logs the event's type and timestamp,
 * never its fields.
 *
 * <p>An event carries user-authored text beside a user id, so logging the event itself would put a
 * health goal and the person it belongs to in every log line, for every event added later as well.
 */
class SpringDomainEventPublisherTest {

    private static final String TITLE = "Quit drinking";
    private static final UUID USER_ID = UUID.fromString("0f2c8f5a-2a4e-4a1d-8f0a-3c5b9d1e77a1");
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 9, 16, 10, 15);

    /** Shaped like the real events: an id and a user-authored title, both in its {@code toString}. */
    private record UpgradeRenamed(UUID userId, String title, LocalDateTime occurredAt) implements DomainEvent {}

    private final Logger logger = (Logger) LoggerFactory.getLogger(SpringDomainEventPublisher.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final List<Object> published = new ArrayList<>();
    private Level previousLevel;

    @BeforeEach
    void captureTheLog() {
        // The line is DEBUG, so it is enabled here: a disabled line would pass any assertion about content.
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseTheLog() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    @Test
    void GivenAnEvent_WhenItIsPublished_ThenItReachesSpringsPublisherUnchanged() {
        UpgradeRenamed event = new UpgradeRenamed(USER_ID, TITLE, OCCURRED_AT);

        new SpringDomainEventPublisher(published::add).publish(event);

        assertThat(published).containsExactly(event);
    }

    @Test
    void GivenAnEventCarryingPersonalData_WhenItIsPublished_ThenTheLogLineNamesOnlyItsTypeAndTime() {
        new SpringDomainEventPublisher(published::add).publish(new UpgradeRenamed(USER_ID, TITLE, OCCURRED_AT));

        assertThat(appender.list).singleElement().satisfies(line -> {
            assertThat(line.getFormattedMessage())
                    .contains(UpgradeRenamed.class.getSimpleName())
                    .contains(OCCURRED_AT.toString())
                    .doesNotContain(TITLE)
                    .doesNotContain(USER_ID.toString());
        });
    }
}
