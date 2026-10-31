package com.healthupgrades.common.websocket;

import static org.assertj.core.api.Assertions.assertThat;

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

        assertThat(errorMessage(handler.handleClientMessageProcessingError(connect, wrapped)))
                .isEqualTo(StompRefusalErrorHandler.NOT_PROCESSED);
    }

    @Test
    void GivenAFrameThatCouldNotBeDecoded_WhenItsErrorFrameIsBuilt_ThenItSaysNotProcessed() {
        // Spring has no client message to pass when decoding failed, and the decoder's text quotes input.
        Message<byte[]> error = handler.handleClientMessageProcessingError(
                null, new StompConversionException("Illegal header: 'evil\\c'"));

        assertThat(errorMessage(error)).isEqualTo(StompRefusalErrorHandler.NOT_PROCESSED);
    }

    @Test
    void GivenARefusedFrameThatAskedForAReceipt_WhenItsErrorFrameIsBuilt_ThenItCarriesTheReceiptId() {
        // STOMP 1.2: an ERROR caused by a frame with a receipt header names that receipt.
        StompHeaderAccessor subscribe = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subscribe.setReceipt("r-7");
        Message<byte[]> frame = MessageBuilder.createMessage(NO_PAYLOAD, subscribe.getMessageHeaders());

        Message<byte[]> error = handler.handleClientMessageProcessingError(
                frame, new StompFrameRefusedException(Reason.REFUSED, "Refused a SUBSCRIBE"));

        assertThat(accessor(error).getReceiptId()).isEqualTo("r-7");
    }

    @Test
    void GivenTheSessionEndedFrame_WhenItIsSentToTheClient_ThenItsWordsAreUnchanged() {
        StompHeaderAccessor sessionEnded = StompHeaderAccessor.create(StompCommand.ERROR);
        sessionEnded.setMessage(StompSessionRegistry.SESSION_ENDED);
        Message<byte[]> frame = MessageBuilder.createMessage(NO_PAYLOAD, sessionEnded.getMessageHeaders());

        assertThat(errorMessage(handler.handleErrorMessageToClient(frame)))
                .isEqualTo(StompSessionRegistry.SESSION_ENDED);
    }

    private static Message<byte[]> clientFrame(StompCommand command) {
        return MessageBuilder.createMessage(
                NO_PAYLOAD, StompHeaderAccessor.create(command).getMessageHeaders());
    }

    private static String errorMessage(Message<byte[]> error) {
        StompHeaderAccessor accessor = accessor(error);
        assertThat(accessor.getCommand()).isEqualTo(StompCommand.ERROR);
        return accessor.getMessage();
    }

    private static StompHeaderAccessor accessor(Message<byte[]> message) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        return accessor != null ? accessor : StompHeaderAccessor.wrap(message);
    }
}
