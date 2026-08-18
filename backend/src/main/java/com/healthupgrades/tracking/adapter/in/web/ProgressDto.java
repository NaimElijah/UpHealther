package com.healthupgrades.tracking.adapter.in.web;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A logged progress entry as returned on the wire.
 *
 * <p>Which value field is meaningful depends on the upgrade's tracking type; the others are null.
 * {@code completed} is the server's verdict, not the client's claim — for a configured upgrade it is
 * recomputed from the target when the entry is recorded.
 *
 * @param id           the entry's identifier
 * @param upgradeId    the upgrade it was logged against
 * @param userId       the owner
 * @param date         the day the entry is for
 * @param completed    whether it counts as a success
 * @param numericValue NUMERIC tracking: the value achieved, else null
 * @param unit         NUMERIC tracking: the unit that value is in, else null
 * @param rating       RATING tracking: the one-to-five self-rating, else null
 * @param note         free-text note, and the scored field under TEXT tracking
 * @param createdAt    when the entry was logged, which may differ from {@code date}
 */
public record ProgressDto(
        UUID id,
        UUID upgradeId,
        UUID userId,
        LocalDate date,
        Boolean completed,
        Double numericValue,
        String unit,
        Integer rating,
        String note,
        LocalDateTime createdAt
) {}
