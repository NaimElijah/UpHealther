package com.healthupgrades.auth.domain;

import com.healthupgrades.user.api.UserDto;

public record TokenPair(String token, UserDto user) {}
