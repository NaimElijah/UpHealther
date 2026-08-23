package com.healthupgrades.common.websocket;

import com.healthupgrades.common.observability.StompTracingChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;

/**
 * STOMP-over-WebSocket wiring for real-time notification push.
 *
 * <p>The broker is Spring's in-memory simple broker — there is no external message broker, which is why
 * push only reaches clients connected to <em>this</em> instance. Horizontal scaling would need a real
 * broker relay; until then a notification is also persisted, so a client that missed the push still sees
 * it on the next fetch.
 *
 * <p>The endpoint is left unauthenticated in {@code SecurityConfig} on purpose: the JWT cannot travel on
 * a browser's WebSocket handshake, so {@link JwtChannelInterceptor} authenticates the STOMP CONNECT
 * frame instead.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtChannelInterceptor jwtChannelInterceptor;
    private final StompTracingChannelInterceptor tracingChannelInterceptor;

    @Value("${app.cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    /** Registers the {@code /ws} endpoint, permitting the same origins as the REST API. */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Native WebSocket (no SockJS). Same-origin requests via the Vite/nginx proxy need no CORS,
        // but allowedOriginPatterns lets a different-origin frontend connect when configured.
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        registry.addEndpoint("/ws").setAllowedOriginPatterns(origins);
    }

    /**
     * Configures destinations: {@code /queue} and {@code /topic} for broker-managed messages,
     * {@code /app} for inbound frames, and {@code /user} for per-session routing.
     *
     * <p>The user prefix is what makes {@code convertAndSendToUser} deliver to one subscriber only; the
     * name it routes by is the user id set by {@link JwtChannelInterceptor}.
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue", "/topic");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        // The broker channel is the middle hop of convertAndSendToUser; tracing it keeps a push joined
        // to whatever caused it. It hangs off the registry rather than being a configurer method.
        registry.configureBrokerChannel().interceptors(tracingChannelInterceptor);
    }

    /**
     * Puts {@link JwtChannelInterceptor} on the inbound channel so CONNECT frames are authenticated.
     *
     * <p>Tracing is registered <b>first</b>, and the order is load-bearing rather than cosmetic:
     * {@code JwtChannelInterceptor} rejects a bad CONNECT by throwing from its own {@code preSend}, and
     * the chain unwinds completion callbacks in reverse, so only an interceptor ahead of it still has a
     * span open to record that rejection on. A rejected connection is exactly the event worth
     * correlating.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(tracingChannelInterceptor, jwtChannelInterceptor);
    }

    /**
     * Traces the outbound half too, so a push can be joined to the request or scheduled run that caused
     * it: {@code convertAndSendToUser} hops through the broker channel and then the client-outbound
     * channel, each on its own thread.
     */
    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(tracingChannelInterceptor);
    }
}
