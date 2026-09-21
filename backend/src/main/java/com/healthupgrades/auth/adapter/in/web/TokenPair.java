package com.healthupgrades.auth.adapter.in.web;

import com.healthupgrades.user.adapter.in.web.UserDto; // reused published presentation model

import java.time.Instant;

/**
 * Web response for a successful authentication: the issued access token, when it lapses, and the
 * authenticated user's public view.
 *
 * <p>Named {@code accessToken} rather than {@code token} because there are now two credentials and
 * only one of them is in this body. The other is the refresh credential, which is in a cookie the
 * client cannot read and must not try to.
 *
 * <p>{@code expiresAt} is here so the client can renew <em>before</em> a request fails rather than
 * after. Without it the only way to discover expiry is to be refused, which costs a round trip and
 * a retry on whichever request happened to be first.
 *
 * @param accessToken the short-lived bearer token, for the client to hold in memory
 * @param expiresAt   when it stops being accepted
 * @param user        the authenticated account
 */
public record TokenPair(String accessToken, Instant expiresAt, UserDto user) {}
