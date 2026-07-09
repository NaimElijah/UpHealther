package com.healthupgrades.auth.adapter.in.web;

import com.healthupgrades.user.adapter.in.web.UserDto; // reused published presentation model

/**
 * Web response for a successful authentication: the issued JWT plus the authenticated user's public view.
 */
public record TokenPair(String token, UserDto user) {}
