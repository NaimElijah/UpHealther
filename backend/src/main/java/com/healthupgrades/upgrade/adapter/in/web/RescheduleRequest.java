package com.healthupgrades.upgrade.adapter.in.web;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record RescheduleRequest(@NotNull LocalDate newDate) {}
