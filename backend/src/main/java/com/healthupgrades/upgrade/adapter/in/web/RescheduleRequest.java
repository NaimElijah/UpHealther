package com.healthupgrades.upgrade.adapter.in.web;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Body for the reschedule endpoint.
 *
 * @param newDate the new planned start date; required
 */
public record RescheduleRequest(@NotNull LocalDate newDate) {}
