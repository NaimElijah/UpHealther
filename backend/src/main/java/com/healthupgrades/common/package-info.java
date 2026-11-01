/**
 * The shared kernel and the cross-cutting adapters that every bounded context uses.
 *
 * <p>Two kinds of code live here.
 *
 * <p><strong>The kernel</strong>, in {@code common.domain}: the vocabulary the contexts share. That is the
 * {@link com.healthupgrades.common.domain.event.DomainEvent} marker (each event itself belongs to the
 * context that raises it), the exceptions that carry the HTTP status contract
 * ({@code common.domain.exception}), the audit vocabulary ({@code common.domain.audit}) and the outbound
 * ports that a context or an adapter implements ({@code common.domain.port.out}). It imports nothing but
 * {@code java.*} and itself.
 *
 * <p><strong>Cross-cutting adapters</strong>, most of which do not have {@code adapter} in their package
 * name:
 *
 * <ul>
 *   <li>{@code common.adapter}: the global exception handler, the Jackson module that refuses U+0000, the
 *       audit trail written to a log stream, and the Spring-backed domain-event publisher;</li>
 *   <li>{@code common.security}: JWT issue and verification, the bearer-token filter, and the Spring
 *       Security configuration and principal;</li>
 *   <li>{@code common.websocket}: STOMP configuration, authentication and frame authorisation, and the
 *       registry that closes sockets when a session ends;</li>
 *   <li>{@code common.observability}: trace-id propagation, the {@code X-Trace-Id} header and job
 *       metrics;</li>
 *   <li>{@code common.ratelimit}: the in-process rate limit on the anonymous authentication
 *       endpoints;</li>
 *   <li>{@code common.time}: {@code ServerZone}, the one zone (UTC) the server keeps time in.</li>
 * </ul>
 *
 * <p>Rules a maintainer must keep:
 *
 * <ul>
 *   <li><strong>The kernel depends on no bounded context.</strong> Where it needs something a context
 *       knows, it declares an outbound port in {@code common.domain.port.out} and the context supplies it:
 *       {@code auth} implements {@code SessionStatusPort} and {@code user} implements
 *       {@code AccountCredentialsPort} (ADR-062, enforced by {@code HexagonalArchitectureTest}).</li>
 *   <li><strong>A context's {@code application} and {@code domain} layers may reach {@code common} only in
 *       {@code common.domain}.</strong> Every other package here is an adapter by what it holds, whatever
 *       its name, and a package added later is out of the core's reach from the start (ADR-064, enforced
 *       by {@code HexagonalArchitectureTest}). Where the core needs what an adapter here does, it
 *       declares a port of its own.</li>
 *   <li><strong>A new audited operation adds a constant to {@code AuditAction}</strong>, not a log
 *       statement (ADR-011).</li>
 *   <li><strong>A context names its own conflict by subclassing {@code ConflictException}</strong>, so
 *       this package never names a context's type.</li>
 *   <li><strong>Every {@code @Scheduled} cron names {@code ServerZone.ID}</strong>, so the schedule and
 *       the injected clock agree on the zone ({@code ServerTimeArchitectureTest}).</li>
 * </ul>
 */
package com.healthupgrades.common;
