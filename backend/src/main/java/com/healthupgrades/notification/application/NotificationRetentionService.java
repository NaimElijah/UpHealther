package com.healthupgrades.notification.application;

import com.healthupgrades.notification.domain.port.out.NotificationRepositoryPort;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Period;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Deletes every user's notifications once they are ninety days old (NFR-60, ADR-075).
 *
 * <p>A class of its own rather than a method on {@link NotificationService}, because a controller holds that
 * service, and this deletes rows of every account: only the nightly job holds this one.
 *
 * <p>Nothing else removes a notification, and the messages quote upgrade titles, so without it the table
 * keeps personal data for as long as the account exists, long after the API, which serves only the newest
 * fifty, can show it.
 */
@Service
@RequiredArgsConstructor
public class NotificationRetentionService {

    /** How long a notification is kept: the owner's decision on #340 (ADR-075). */
    static final Period RETENTION = Period.ofDays(90);

    /**
     * How many rows one statement deletes. Each batch is a transaction of its own, so a night that finds a
     * large backlog holds its locks briefly and often, rather than once for the whole table.
     */
    static final int BATCH_SIZE = 1_000;

    /**
     * The most batches one run deletes, twenty thousand rows. The job shares Boot's one scheduler thread with
     * the reminder sweep, which sends a minute's reminders only in that minute (FR-31), so a run must give
     * the thread back within seconds. A night's normal volume, one day's notifications ninety days on, is far
     * below it; a larger backlog drains over the following nights (ADR-075).
     */
    static final int MAX_BATCHES_PER_RUN = 20;

    private final NotificationRepositoryPort repository; // outbound persistence port
    private final Clock clock; // the server's clock, in UTC (ADR-020)

    /**
     * Deletes the notifications created before the retention period began, a batch at a time, until a batch
     * comes back short or {@link #MAX_BATCHES_PER_RUN} have run. What is left then waits for the next run.
     *
     * <p>A notification created exactly {@link #RETENTION} ago is kept; one created any time before is
     * deleted. Not one transaction: a batch that committed stays deleted if a later one fails.
     *
     * @return how many were deleted
     */
    public int deleteExpired() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(RETENTION);
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int deleted = repository.deleteCreatedBefore(cutoff, BATCH_SIZE);
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }
        return total;
    }
}
