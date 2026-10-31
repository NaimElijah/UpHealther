package com.healthupgrades.common.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.healthupgrades.common.domain.audit.AuditAction;
import com.healthupgrades.common.domain.audit.AuditEvent;
import com.healthupgrades.common.domain.audit.AuditOutcome;
import com.healthupgrades.common.security.BearerAuthentication;
import com.healthupgrades.common.security.BearerTokenAuthenticator;
import com.healthupgrades.common.websocket.StompFrameRefusedException.Reason;
import com.healthupgrades.support.AUser;
import com.healthupgrades.support.RecordingAuditTrail;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

/**
 * The STOMP side of FR-5: a WebSocket session is authenticated by its CONNECT frame, because a browser
 * cannot put a bearer token on the handshake.
 *
 * <p>The session principal is named by the user id, which is what {@code convertAndSendToUser} routes by,
 * so the name asserted here is the one that decides whose notifications a socket receives.
 *
 * <p>Authenticating CONNECT is only half of it. A STOMP session can name any destination it likes on a
 * later frame, so the frames after CONNECT are authorised here too: a subscription must be the one
 * destination this application pushes to, and every command but CONNECT, STOMP, SUBSCRIBE, UNSUBSCRIBE and
 * DISCONNECT is refused outright: a SEND because no {@code @MessageMapping} exists for one to reach, the
 * server's own frames because a client has no business sending them.
 */
@ExtendWith(MockitoExtension.class)
class JwtChannelInterceptorTest {

    private static final String TOKEN = "a.signed.token";
    private static final UUID SESSION_ID = UUID.randomUUID();

    /** The user a STOMP frame's token stands for. */
    private static final UUID STOMP_USER = UUID.fromString("9b7e2d4c-1f6a-4c8e-b3d5-0a2e7f9c6b18");

    @Mock
    BearerTokenAuthenticator authenticator;

    private final MessageChannel channel = mock(MessageChannel.class);
    /** A real one, not a mock: AuditTrail.recording is a default method. */
    private final RecordingAuditTrail auditTrail = new RecordingAuditTrail();

    private JwtChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new JwtChannelInterceptor(authenticator, auditTrail);
    }

    @Test
    void GivenAConnectWithAUsableToken_WhenItArrives_ThenTheSessionPrincipalIsTheUsersId() {
        UUID userId = UUID.randomUUID();
        when(authenticator.authenticateWithSession(TOKEN)).thenReturn(Optional.of(signedIn(userId)));

        Message<?> passed = interceptor.preSend(frame(StompCommand.CONNECT, "Bearer " + TOKEN), channel);

        Principal principal = StompHeaderAccessor.wrap(passed).getUser();
        assertThat(principal).isNotNull();
        assertThat(principal.getName()).isEqualTo(userId.toString());
    }

    @Test
    void GivenAConnectWithAUsableToken_WhenItArrives_ThenTheSessionPrincipalNamesTheSessionItWasOpenedIn() {
        // Kept so that ending that session can find this socket and close it, and leave the account's
        // sockets opened in its other sessions alone: sign-out is per device.
        when(authenticator.authenticateWithSession(TOKEN)).thenReturn(Optional.of(signedIn(UUID.randomUUID())));

        Message<?> passed = interceptor.preSend(frame(StompCommand.CONNECT, "Bearer " + TOKEN), channel);

        assertThat(StompHeaderAccessor.wrap(passed).getUser())
                .isInstanceOfSatisfying(
                        StompPrincipal.class,
                        principal -> assertThat(principal.sessionId()).isEqualTo(SESSION_ID));
    }

    @Test
    void GivenAConnectWithAnUnusableToken_WhenItArrives_ThenTheConnectionIsRefused() {
        when(authenticator.authenticateWithSession(TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "Bearer " + TOKEN), channel))
                .isInstanceOfSatisfying(StompFrameRefusedException.class, refusedAs(Reason.UNAUTHENTICATED));
    }

    @Test
    void GivenAConnectWithNoToken_WhenItArrives_ThenTheConnectionIsRefusedWithoutAskingTheAuthenticator() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, null), channel))
                .isInstanceOfSatisfying(StompFrameRefusedException.class, refusedAs(Reason.UNAUTHENTICATED));

        verify(authenticator, never()).authenticateWithSession(any());
    }

    @Test
    void GivenAConnectWithANonBearerCredential_WhenItArrives_ThenTheConnectionIsRefused() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "Basic abc"), channel))
                .isInstanceOfSatisfying(StompFrameRefusedException.class, refusedAs(Reason.UNAUTHENTICATED));
    }

    // STOMP is the 1.2 spec's alias for CONNECT, so a client that sends it must meet the same door.

    @Test
    void GivenAStompFrameWithAUsableToken_WhenItArrives_ThenItIsAuthenticatedAndAuditedLikeAConnect() {
        when(authenticator.authenticateWithSession(TOKEN)).thenReturn(Optional.of(signedIn(STOMP_USER)));

        Message<?> passed = interceptor.preSend(frame(StompCommand.STOMP, "Bearer " + TOKEN), channel);

        assertThat(StompHeaderAccessor.wrap(passed).getUser())
                .isNotNull()
                .extracting(Principal::getName)
                .isEqualTo(STOMP_USER.toString());
        assertThat(auditTrail.only(AuditAction.AUTH_CONNECT))
                .hasValue(AuditEvent.allowed(AuditAction.AUTH_CONNECT, STOMP_USER, STOMP_USER));
    }

    @Test
    void GivenAStompFrameWithAnUnusableToken_WhenItArrives_ThenItIsRefusedAndAuditedLikeAConnect() {
        when(authenticator.authenticateWithSession(TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.STOMP, "Bearer " + TOKEN), channel))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(auditTrail.only(AuditAction.AUTH_CONNECT))
                .hasValue(new AuditEvent(AuditAction.AUTH_CONNECT, null, null, AuditOutcome.REFUSED));
    }

    // ---- NFR-23: opening a connection is an authentication outcome, allowed or refused ----

    @Test
    void GivenAConnectWithAUsableToken_WhenItArrives_ThenTheConnectionIsAuditedAsAllowedForThatUser() {
        UUID userId = UUID.randomUUID();
        when(authenticator.authenticateWithSession(TOKEN)).thenReturn(Optional.of(signedIn(userId)));

        interceptor.preSend(frame(StompCommand.CONNECT, "Bearer " + TOKEN), channel);

        assertThat(auditTrail.only(AuditAction.AUTH_CONNECT))
                .hasValue(AuditEvent.allowed(AuditAction.AUTH_CONNECT, userId, userId));
    }

    @Test
    void GivenAConnectWithAnUnusableToken_WhenItArrives_ThenTheRefusalIsAuditedWithNoSubject() {
        // A token that does not authenticate proves nothing about whose it was - the same reason a
        // refused login names nobody.
        when(authenticator.authenticateWithSession(TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "Bearer " + TOKEN), channel))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(auditTrail.only(AuditAction.AUTH_CONNECT))
                .hasValue(new AuditEvent(AuditAction.AUTH_CONNECT, null, null, AuditOutcome.REFUSED));
    }

    @Test
    void GivenAConnectWithNoAuthorizationHeader_WhenItArrives_ThenItIsRefusedAndNothingIsAudited() {
        // What a tab sends every five seconds once its session cannot be renewed. Nothing was presented,
        // so, like a refresh with no cookie, it is no attempt; an entry each time would bury the
        // refusals worth reading.
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, null), channel))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(auditTrail.recorded()).isEmpty();
    }

    @Test
    void GivenAConnectWithANonBearerCredential_WhenItArrives_ThenTheRefusalIsAuditedWithNoSubject() {
        // Something was presented, so this one is an attempt - just not with a credential this accepts.
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "Basic abc"), channel))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(auditTrail.only(AuditAction.AUTH_CONNECT))
                .hasValue(new AuditEvent(AuditAction.AUTH_CONNECT, null, null, AuditOutcome.REFUSED));
    }

    @Test
    void GivenTheAuthenticatorFails_WhenAConnectArrives_ThenTheAttemptIsAuditedAsFailedAndTheFaultPropagates() {
        // The authenticator reads the session and the account on every call, so a failure there is an
        // outage, not a refused token, and counting it as a refusal would hide it in ordinary traffic.
        when(authenticator.authenticateWithSession(TOKEN))
                .thenThrow(new DataAccessResourceFailureException("the database is unreachable"));

        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "Bearer " + TOKEN), channel))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(auditTrail.only(AuditAction.AUTH_CONNECT))
                .hasValue(new AuditEvent(AuditAction.AUTH_CONNECT, null, null, AuditOutcome.FAILED));
    }

    @Test
    void GivenARefusedSubscribeOrSend_WhenItArrives_ThenNothingIsAudited() {
        // These authorise frames on a connection already authenticated; they are not authentication
        // outcomes, and they stay WARN lines. Pinned because the decision is deliberate.
        assertThatThrownBy(() -> interceptor.preSend(
                        frameFrom(StompCommand.SUBSCRIBE, "/topic/notifications", session()), channel))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> interceptor.preSend(frameFrom(StompCommand.SEND, "/app/anything", session()), channel))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(auditTrail.recorded()).isEmpty();
    }

    @Test
    void GivenASubscribeToItsOwnUserQueue_WhenItArrives_ThenItPasses() {
        Message<?> subscribe = frameFrom(StompCommand.SUBSCRIBE, "/user/queue/notifications", session());

        assertThat(interceptor.preSend(subscribe, channel)).isSameAs(subscribe);
    }

    /**
     * STOMP 1.2 obliges a subscriber in {@code client} or {@code client-individual} mode to acknowledge
     * each MESSAGE, and an ACK is refused. Accepted, such a subscription would be refused at its first
     * acknowledgement and reconnect into the same refusal for ever; refused at SUBSCRIBE, it fails once,
     * where the cause is visible.
     */
    @ParameterizedTest
    @ValueSource(strings = {"client", "client-individual"})
    void GivenASubscribeInAnAckModeThatNeedsAnAck_WhenItArrives_ThenItIsRefused(String ack) {
        Message<?> subscribe = subscribeWithAck(ack);

        assertThatThrownBy(() -> interceptor.preSend(subscribe, channel))
                .isInstanceOfSatisfying(StompFrameRefusedException.class, refusedAs(Reason.REFUSED));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "auto")
    void GivenASubscribeInAutoAckMode_WhenItArrives_ThenItPasses(String ack) {
        // A missing ack header is auto mode too, by the spec's default, and is what the SPA sends.
        Message<?> subscribe = subscribeWithAck(ack);

        assertThat(interceptor.preSend(subscribe, channel)).isSameAs(subscribe);
    }

    @Test
    void GivenASubscribeToAnotherSessionsResolvedQueue_WhenItArrives_ThenItIsRefused() {
        // The destination the broker resolves /user/queue/notifications into. A client that names it
        // directly is asking for a queue belonging to whichever session owns that suffix, which is the
        // whole reason an allowlist of one destination is not paranoia.
        Message<?> subscribe = frameFrom(StompCommand.SUBSCRIBE, "/queue/notifications-userxyz789", session());

        assertThatThrownBy(() -> interceptor.preSend(subscribe, channel)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void GivenASubscribeToATopic_WhenItArrives_ThenItIsRefused() {
        // /topic is STOMP's conventional broadcast prefix: nothing here publishes to one, and the broker
        // does not serve it, but a subscriber to a topic would receive whatever a later feature did.
        Message<?> subscribe = frameFrom(StompCommand.SUBSCRIBE, "/topic/notifications", session());

        assertThatThrownBy(() -> interceptor.preSend(subscribe, channel))
                .isInstanceOfSatisfying(StompFrameRefusedException.class, refusedAs(Reason.REFUSED));
    }

    @Test
    void GivenASubscribeFromASessionThatNeverConnected_WhenItArrives_ThenItIsRefused() {
        Message<?> subscribe = frameFrom(StompCommand.SUBSCRIBE, "/user/queue/notifications", null);

        assertThatThrownBy(() -> interceptor.preSend(subscribe, channel)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void GivenASendFrame_WhenItArrives_ThenItIsRefusedBecauseNothingServerSideReceivesOne() {
        // There is no @MessageMapping in the application, so a SEND can only be probing for one.
        Message<?> send = frameFrom(StompCommand.SEND, "/app/anything", session());

        assertThatThrownBy(() -> interceptor.preSend(send, channel))
                .isInstanceOfSatisfying(StompFrameRefusedException.class, refusedAs(Reason.REFUSED));
    }

    /**
     * Finding D1.1: a frame STOMP defines for the server to send, arriving from a client.
     *
     * <p>Spring's decoder takes any command from a client, and a client's MESSAGE is routed exactly as a
     * SEND would be. Passed, one addressed to {@code /user/<anotherUsersId>/queue/notifications} was
     * delivered into that user's socket as a notification they had never been sent.
     */
    @ParameterizedTest
    @EnumSource(
            value = StompCommand.class,
            names = {"MESSAGE", "CONNECTED", "RECEIPT", "ERROR"})
    void GivenAFrameOnlyTheServerSends_WhenAClientSendsIt_ThenItIsRefused(StompCommand command) {
        Message<?> frame = frameFrom(command, "/user/" + UUID.randomUUID() + "/queue/notifications", session());

        assertThatThrownBy(() -> interceptor.preSend(frame, channel))
                .isInstanceOfSatisfying(StompFrameRefusedException.class, refusedAs(Reason.REFUSED));
    }

    @ParameterizedTest
    @EnumSource(
            value = StompCommand.class,
            names = {"ACK", "NACK", "BEGIN", "COMMIT", "ABORT"})
    void GivenAnAcknowledgementOrTransactionFrame_WhenItArrives_ThenItIsRefusedBecauseNothingHereUsesOne(
            StompCommand command) {
        // The SPA subscribes with the default ack mode and opens no transaction, so a client sending one
        // of these is not the SPA. Refusing them is the least the socket needs to allow.
        Message<?> frame = frameFrom(command, null, session());

        assertThatThrownBy(() -> interceptor.preSend(frame, channel))
                .isInstanceOfSatisfying(StompFrameRefusedException.class, refusedAs(Reason.REFUSED));
    }

    /**
     * ADR-079's decision itself: every command but the five the allow-list names is refused. The two
     * tests above say why each group is refused; this one is the complement of the allow-list, so a
     * deny-list rewrite that missed a command, or a command a later Spring adds, fails here.
     */
    @ParameterizedTest
    @EnumSource(
            value = StompCommand.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = {"CONNECT", "STOMP", "SUBSCRIBE", "UNSUBSCRIBE", "DISCONNECT"})
    void GivenAnyCommandOutsideTheAllowList_WhenItArrives_ThenItIsRefused(StompCommand command) {
        Message<?> frame = frameFrom(command, null, session());

        assertThatThrownBy(() -> interceptor.preSend(frame, channel))
                .isInstanceOfSatisfying(StompFrameRefusedException.class, refusedAs(Reason.REFUSED));
    }

    @Test
    void GivenACommandOutsideTheAllowList_WhenItIsRefused_ThenTheRefusalNamesTheCommandInAGrammaticalSentence() {
        // The same words go into the WARN line. "a ACK frame" or "a ERROR frame" reads as a typo in the
        // log, so the command is named where no article has to agree with it.
        Message<?> ack = frameFrom(StompCommand.ACK, null, session());

        assertThatThrownBy(() -> interceptor.preSend(ack, channel))
                .hasMessage("Refused a frame of command ACK, which this application's client never sends");
    }

    @Test
    void GivenASocketHoldingASubscription_WhenASecondSubscribeArrives_ThenItIsRefused() {
        // S5.A5: each SUBSCRIBE registers with the broker, so a signed-in socket streaming them with
        // fresh ids would grow the single instance's heap without limit. The client needs one.
        Map<String, Object> socket = new ConcurrentHashMap<>();
        Principal user = session();
        interceptor.preSend(subscribeOn(socket, "sub-0", user), channel);

        assertThatThrownBy(() -> interceptor.preSend(subscribeOn(socket, "sub-1", user), channel))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A socket holds one subscription for its life: an UNSUBSCRIBE passes, and frees nothing.
     *
     * <p>This test used to pin the opposite, a second SUBSCRIBE accepted once an UNSUBSCRIBE had released
     * the first. That release could be raced: Spring hands each inbound frame to a pool, in no order
     * unless {@code preserveReceiveOrder} is set, and an UNSUBSCRIBE reaches the broker by a different
     * route from a SUBSCRIBE, so one that ran first removed nothing and left its SUBSCRIBE held until the
     * socket closed, which is S5.A5's growth again. The SPA subscribes once per socket and never
     * unsubscribes, so nothing legitimate needs a second.
     */
    @Test
    void GivenASocketThatUnsubscribed_WhenItSubscribesAgain_ThenItIsStillRefused() {
        Map<String, Object> socket = new ConcurrentHashMap<>();
        Principal user = session();
        interceptor.preSend(subscribeOn(socket, "sub-0", user), channel);
        Message<?> unsubscribe = unsubscribeOn(socket, "sub-0", user);
        assertThat(interceptor.preSend(unsubscribe, channel)).isSameAs(unsubscribe);

        assertThatThrownBy(() -> interceptor.preSend(subscribeOn(socket, "sub-1", user), channel))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void GivenAnUnsubscribeNamingAnotherId_WhenTheSocketSubscribesAgain_ThenItIsStillRefused() {
        // Nothing is released whatever the UNSUBSCRIBE names, an id the socket never took included.
        Map<String, Object> socket = new ConcurrentHashMap<>();
        Principal user = session();
        interceptor.preSend(subscribeOn(socket, "sub-0", user), channel);
        interceptor.preSend(unsubscribeOn(socket, "not-held", user), channel);

        assertThatThrownBy(() -> interceptor.preSend(subscribeOn(socket, "sub-1", user), channel))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void GivenASubscribeWithNoSubscriptionId_WhenItArrives_ThenItIsRefused() {
        // STOMP requires the id, and no real client omits it. Accepted, a plain map would store no id
        // under the held key, so the socket would never count as holding one and the limit would reopen.
        Map<String, Object> socket = new HashMap<>();
        Principal user = session();

        assertThatThrownBy(() -> interceptor.preSend(subscribeOn(socket, null, user), channel))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void GivenASubscribeOnASocketWithNoAttributes_WhenItArrives_ThenItIsRefused() {
        // Without the socket's attributes there is nowhere to record what it holds, so refusing is the
        // only way to keep the one-subscription limit closed.
        Message<?> subscribe =
                frameOn(null, StompCommand.SUBSCRIBE, JwtChannelInterceptor.ALLOWED_SUBSCRIPTION, "sub-0", session());

        assertThatThrownBy(() -> interceptor.preSend(subscribe, channel)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void GivenTwoSocketsOfOneAccount_WhenEachSubscribes_ThenBothPass() {
        // Per socket, not per account: two tabs each hold their own subscription.
        Principal user = session();
        interceptor.preSend(subscribeOn(new ConcurrentHashMap<>(), "sub-0", user), channel);

        Message<?> secondTab = subscribeOn(new ConcurrentHashMap<>(), "sub-0", user);

        assertThat(interceptor.preSend(secondTab, channel)).isSameAs(secondTab);
    }

    @Test
    void GivenAHeartbeat_WhenItArrives_ThenItPasses() {
        // A heartbeat carries no command at all. Reading one as an unauthorised frame would close every
        // idle socket on its first keepalive.
        StompHeaderAccessor accessor = StompHeaderAccessor.createForHeartbeat();
        accessor.setLeaveMutable(true);
        Message<byte[]> heartbeat = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThat(interceptor.preSend(heartbeat, channel)).isSameAs(heartbeat);
    }

    /**
     * A heartbeat is the one message without a command that passes. Spring's own senders onto the inbound
     * channel, a client's decoded frame and the DISCONNECT for a closed socket, each carry STOMP headers
     * with a command, so anything else is no frame Spring produced and is refused rather than waved
     * through as a keepalive.
     */
    @Test
    void GivenAMessageWithNoStompHeaders_WhenItArrives_ThenItIsRefused() {
        Message<byte[]> bare = MessageBuilder.withPayload(new byte[0]).build();

        assertThatThrownBy(() -> interceptor.preSend(bare, channel))
                .isInstanceOfSatisfying(StompFrameRefusedException.class, refusedAs(Reason.REFUSED));
    }

    @Test
    void GivenStompHeadersWithNoCommandThatAreNoHeartbeat_WhenTheyArrive_ThenTheyAreRefused() {
        StompHeaderAccessor accessor =
                StompHeaderAccessor.wrap(MessageBuilder.withPayload(new byte[0]).build());
        accessor.setLeaveMutable(true);
        Message<byte[]> commandless = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(commandless, channel))
                .isInstanceOfSatisfying(StompFrameRefusedException.class, refusedAs(Reason.REFUSED));
    }

    @Test
    void GivenAnUnsubscribe_WhenItArrives_ThenItPasses() {
        Message<?> unsubscribe = frameFrom(StompCommand.UNSUBSCRIBE, null, session());

        assertThat(interceptor.preSend(unsubscribe, channel)).isSameAs(unsubscribe);
    }

    @Test
    void GivenADisconnect_WhenItArrives_ThenItPasses() {
        // Refusing this would leave the session to be torn down by a timeout instead of a clean close.
        Message<?> disconnect = frameFrom(StompCommand.DISCONNECT, null, session());

        assertThat(interceptor.preSend(disconnect, channel)).isSameAs(disconnect);
    }

    /** What the authenticator answers for a usable token: the account, and the session it was issued in. */
    private static BearerAuthentication signedIn(UUID userId) {
        return new BearerAuthentication(AUser.principalFor(userId), SESSION_ID);
    }

    /** The principal a CONNECT leaves on the session, which later frames arrive carrying. */
    private static Principal session() {
        return new StompPrincipal(UUID.randomUUID().toString(), UUID.randomUUID());
    }

    /** A frame on a socket of its own, which has held no subscription before it. */
    private static Message<byte[]> frameFrom(StompCommand command, String destination, Principal user) {
        return frameOn(new ConcurrentHashMap<>(), command, destination, "sub-0", user);
    }

    /** A SUBSCRIBE to the one allowed destination, on the socket whose attributes are {@code socket}. */
    private static Message<byte[]> subscribeOn(Map<String, Object> socket, String id, Principal user) {
        return frameOn(socket, StompCommand.SUBSCRIBE, JwtChannelInterceptor.ALLOWED_SUBSCRIPTION, id, user);
    }

    /** A SUBSCRIBE to the one allowed destination naming {@code ack} as its ack mode, or none when null. */
    private static Message<byte[]> subscribeWithAck(String ack) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(JwtChannelInterceptor.ALLOWED_SUBSCRIPTION);
        accessor.setSubscriptionId("sub-0");
        if (ack != null) {
            accessor.setAck(ack);
        }
        accessor.setSessionAttributes(new ConcurrentHashMap<>());
        accessor.setUser(session());
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /** An UNSUBSCRIBE of {@code id}, on the socket whose attributes are {@code socket}. */
    private static Message<byte[]> unsubscribeOn(Map<String, Object> socket, String id, Principal user) {
        return frameOn(socket, StompCommand.UNSUBSCRIBE, null, id, user);
    }

    /**
     * A frame on one socket. Spring hands every frame of a WebSocket session the same attributes map, so
     * sharing {@code socket} between frames is what makes them frames of one socket.
     */
    private static Message<byte[]> frameOn(
            Map<String, Object> socket, StompCommand command, String destination, String id, Principal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        accessor.setSubscriptionId(id);
        accessor.setSessionAttributes(socket);
        accessor.setUser(user);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<byte[]> frame(StompCommand command, String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (authorization != null) {
            accessor.addNativeHeader("Authorization", authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /** The refusal's reason, which decides the words the client's ERROR frame carries (ADR-078). */
    private static Consumer<StompFrameRefusedException> refusedAs(Reason reason) {
        return refusal -> assertThat(refusal.reason()).isEqualTo(reason);
    }
}
