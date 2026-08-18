package com.healthupgrades.upgrade.adapter.in.web;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Body for the plan endpoint.
 *
 * @param plannedStartDate the date the user intends to start; required, and free to be in the past,
 *                         since users plan retroactively
 */
public record PlanRequest(@NotNull LocalDate plannedStartDate) {}
