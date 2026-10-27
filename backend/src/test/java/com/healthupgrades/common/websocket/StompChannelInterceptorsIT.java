package com.healthupgrades.common.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.common.observability.StompTracingChannelInterceptor;
import com.healthupgrades.support.PostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.support.AbstractMessageChannel;

/**
 * NFR-21 for STOMP: the tracing interceptor sits on every channel a frame crosses, and ahead of the
 * authentication on the inbound one.
 *
 * <p>{@code StompTracingChannelInterceptorTest} drives the interceptor by hand, so it passes whether or
 * not {@code WebSocketConfig} registers it anywhere. What this class reads is the channels the running
 * application built. The inbound order is load-bearing: {@link JwtChannelInterceptor} refuses a bad
 * CONNECT by throwing from its {@code preSend}, and only an interceptor registered before it still has
 * a span open to record that refusal on.
 *
 * <p>The annotations match {@code CorrelationIT}'s so the classes share one context.
 */
@AutoConfigureTracing
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StompChannelInterceptorsIT extends PostgresIT {

    @Autowired
    private StompTracingChannelInterceptor tracing;

    @Autowired
    private JwtChannelInterceptor authentication;

    @Autowired
    @Qualifier("clientInboundChannel")
    private AbstractMessageChannel inbound;

    @Autowired
    @Qualifier("brokerChannel")
    private AbstractMessageChannel broker;

    @Autowired
    @Qualifier("clientOutboundChannel")
    private AbstractMessageChannel outbound;

    @Test
    void GivenTheRunningApplication_WhenTheInboundChannelIsRead_ThenTracingIsRegisteredBeforeAuthentication() {
        assertThat(inbound.getInterceptors())
                .as("a CONNECT refused by the JWT interceptor is recorded only by a span opened before it")
                .containsSubsequence(tracing, authentication);
    }

    @Test
    void GivenTheRunningApplication_WhenTheBrokerChannelIsRead_ThenItIsTraced() {
        // The middle hop of convertAndSendToUser: untraced, a push loses the id of whatever caused it.
        assertThat(broker.getInterceptors()).contains(tracing);
    }

    @Test
    void GivenTheRunningApplication_WhenTheOutboundChannelIsRead_ThenItIsTraced() {
        assertThat(outbound.getInterceptors()).contains(tracing);
    }
}
