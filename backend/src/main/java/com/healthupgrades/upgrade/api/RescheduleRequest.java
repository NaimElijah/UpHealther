package com.healthupgrades.upgrade.api;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record RescheduleRequest(@NotNull LocalDate newDate) {}
