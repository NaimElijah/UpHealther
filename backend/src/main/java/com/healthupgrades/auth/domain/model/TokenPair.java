package com.healthupgrades.auth.domain.model;

import com.healthupgrades.user.adapter.in.web.UserDto;

public record TokenPair(String token, UserDto user) {}
