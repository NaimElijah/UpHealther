package com.healthupgrades.user.api;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserDto(
        UUID id,
        String name,
        String email,
        LocalDateTime createdAt
) {}
