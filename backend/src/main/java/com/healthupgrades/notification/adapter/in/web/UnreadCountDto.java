package com.healthupgrades.notification.adapter.in.web;

/**
 * How many of the caller's notifications are unread, as the inbox badge reads it.
 *
 * <p>An object rather than a bare number, so the response can grow a field without breaking a client
 * that parses it. A record rather than a map, so its shape is a type the frontend's generated wire
 * types can name (#168).
 *
 * @param count every unread notification the caller owns, not only the fifty the inbox lists
 */
public record UnreadCountDto(long count) {}
