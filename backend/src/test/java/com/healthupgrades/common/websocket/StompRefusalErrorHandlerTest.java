package com.healthupgrades.common.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.healthupgrades.common.websocket.StompFrameRefusedException.Reason;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompConversionException;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;

/**
 * The words of the ERROR frame for each way an inbound frame can fail, including the two that
 * {@code StompRefusalIT} cannot provoke over a socket: an outage while authenticating, and a frame that
 * could not be decoded. And the one ERROR frame the server sends on its own, which must pass through as
 * it was written, because the browser ends the session on exactly those words (ADR-066).
 *
 * <p>Every frame the handler builds is read through {@link MessageHeaderAccessor#getAccessor}, never
 * re-wrapped, because that is how spring-websocket 7.0.9's {@code StompSubProtocolHandler} reads it, and
 * it asserts the accessor is there. The words are literals, as in {@code StompRefusalIT}: they are the wire
 * contract.
 */
class StompRefusalErrorHandlerTest {

    /** What spring-messaging 7.0.9 wraps an interceptor's exception in, word for word. */
    private static final String CHANNEL_WRAPPER =
            "Failed to send message to ExecutorSubscribableChannel[clientInboundChannel]";

    private static final byte[] NO_PAYLOAD = new byte[0];

    private final StompRefusalErrorHandler handler = new StompRefusalErrorHandler();

    @Test
    void GivenARefusalWrappedByTheInboundChannel_WhenItsErrorFrameIsBuilt_ThenItSaysTheReasonAndNotTheWrapper() {
        Message<byte[]> connect = clientFrame(StompCommand.CONNECT);
        Throwable wrapped = new MessageDeliveryException(
                connect, CHANNEL_WRAPPER, new StompFrameRefusedException(Reason.UNAUTHENTICATED, "server detail"));

        assertThat(errorMessage(handler.handleClientMessageProcessingError(connect, wrapped)))
                .isEqualTo("Unauthenticated");
    }

    @Test
    void GivenARefusedSubscription_WhenItsErrorFrameIsBuilt_ThenItSaysRefused() {
        Message<byte[]> subscribe = clientFrame(StompCommand.SUBSCRIBE);
        Throwable wrapped = new MessageDeliveryException(
                subscribe, CHANNEL_WRAPPER, new StompFrameRefusedException(Reason.REFUSED, "Refused a SUBSCRIBE"));

        assertThat(errorMessage(handler.handleClientMessageProcessingError(subscribe, wrapped)))
                .isEqualTo("Refused");
    }

    @Test
    void GivenAnOutageWhileAuthenticating_WhenItsErrorFrameIsBuilt_ThenItSaysNotProcessedAndNoExceptionText() {
        // Not a refusal: the token may be fine. Calling it Unauthenticated would tell the client to sign
        // in again for a database that is down, and the exception's text names the server's internals.
        Message<byte[]> connect = clientFrame(StompCommand.CONNECT);
        Throwable wrapped = new MessageDeliveryException(
                connect, CHANNEL_WRAPPER, new DataAccessResourceFailureException("Connection to db:5432 refused"));

        Message<byte[]> error = handler.handleClientMessageProcessingError(connect, wrapped);

        assertThat(errorMessage(error)).isEqualTo("Frame not processed");
        assertThat(error.getPayload())
                .as("the body carries nothing, the exception's text least of all")
                .isEmpty();
    }

    @Test
    void GivenAFrameThatCouldNotBeDecoded_WhenItsErrorFrameIsBuilt_ThenItSaysNotProcessed() {
        // Spring has no client message to pass when decoding failed, and the decoder's text quotes input.
        Message<byte[]> error = handler.handleClientMessageProcessingError(
                null, new StompConversionException("Illegal header: 'evil\\c'"));

        assertThat(errorMessage(error)).isEqualTo("Frame not processed");
        assertThat(error.getPayload()).isEmpty();
    }

    @Test
    void GivenARefusedFrameThatAskedForAReceipt_WhenItsErrorFrameIsBuilt_ThenItCarriesTheReceiptId() {
        // STOMP 1.2: an ERROR caused by a frame with a receipt header names that receipt.
        StompHeaderAccessor subscribe = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subscribe.setReceipt("r-7");
        Message<byte[]> frame = MessageBuilder.createMessage(NO_PAYLOAD, subscribe.getMessageHeaders());

        Message<byte[]> error = handler.handleClientMessageProcessingError(
                frame, new StompFrameRefusedException(Reason.REFUSED, "Refused a SUBSCRIBE"));

        assertThat(liveAccessor(error).getReceiptId()).isEqualTo("r-7");
        assertThat(error.getPayload()).isEmpty();
    }

    @Test
    void GivenTheSessionEndedFrame_WhenItIsSentToTheClient_ThenItsWordsAreUnchanged() {
        StompHeaderAccessor sessionEnded = StompHeaderAccessor.create(StompCommand.ERROR);
        sessionEnded.setMessage(StompSessionRegistry.SESSION_ENDED);
        Message<byte[]> frame = MessageBuilder.createMessage(NO_PAYLOAD, sessionEnded.getMessageHeaders());

        // The hand-built input carries no live accessor, so this one frame is read by wrapping it.
        StompHeaderAccessor sent = StompHeaderAccessor.wrap(handler.handleErrorMessageToClient(frame));

        assertThat(sent.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(sent.getMessage()).isEqualTo(StompSessionRegistry.SESSION_ENDED);
    }

    @Test
    void GivenNoReason_WhenARefusalIsBuilt_ThenItFailsThereRatherThanInsideSpring() {
        // Accepted, a null reason would throw inside Spring's error handling instead, and the client
        // would get no ERROR frame at all.
        assertThatThrownBy(() -> new StompFrameRefusedException(null, "detail"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("reason");
    }

    private static Message<byte[]> clientFrame(StompCommand command) {
        return MessageBuilder.createMessage(
                NO_PAYLOAD, StompHeaderAccessor.create(command).getMessageHeaders());
    }

    private static String errorMessage(Message<byte[]> error) {
        StompHeaderAccessor accessor = liveAccessor(error);
        assertThat(accessor.getCommand()).isEqualTo(StompCommand.ERROR);
        return accessor.getMessage();
    }

    /** The accessor Spring will look for on a frame the handler built; without it Spring sends nothing. */
    private static StompHeaderAccessor liveAccessor(Message<byte[]> error) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(error, StompHeaderAccessor.class);
        assertThat(accessor)
                .as(
                        "StompSubProtocolHandler asserts an accessor on the frame it sends, so one without it is never sent")
                .isNotNull();
        return accessor;
    }
}
