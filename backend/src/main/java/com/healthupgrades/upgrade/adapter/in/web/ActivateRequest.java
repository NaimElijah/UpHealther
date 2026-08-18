package com.healthupgrades.upgrade.adapter.in.web;

import java.time.LocalDate;

/**
 * Optional body for the activate endpoint.
 *
 * <p>The whole body may be omitted, which is why every field is optional: activating without one starts
 * the upgrade today.
 *
 * @param startDate the date the upgrade starts running; today when null
 */
public record ActivateRequest(LocalDate startDate) {}
