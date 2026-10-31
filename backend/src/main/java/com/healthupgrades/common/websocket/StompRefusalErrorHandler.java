package com.healthupgrades.common.websocket;

import org.jspecify.annotations.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * Writes the ERROR frame for an inbound STOMP frame the server did not process, in fixed words.
 *
 * <p>Without it, Spring writes the thrown exception's message, and what reaches it from an interceptor
 * is the inbound channel's wrapper: every refusal read "Failed to send message to
 * ExecutorSubscribableChannel[clientInboundChannel]", which names a class inside the server and lets a
 * client tell no refusal from an outage. So the words are chosen here, from what was thrown, and never
 * copied from it:
 * <ul>
 *   <li>a {@link StompFrameRefusedException} anywhere in the cause chain is told by its
 *       {@link StompFrameRefusedException.Reason#frameMessage() reason};</li>
 *   <li>anything else, an outage while authenticating or a frame that could not be decoded, is
 *       {@link #NOT_PROCESSED}.</li>
 * </ul>
 *
 * <p>Only frames from the client come through here. The ERROR frame the server sends on its own, when a
 * session ends ({@link StompSessionRegistry#SESSION_ENDED}), goes through
 * {@link #handleErrorMessageToClient}, which this class leaves as Spring wrote it: it returns that frame
 * unchanged.
 */
public class StompRefusalErrorHandler extends StompSubProtocolErrorHandler {

    /** The ERROR frame's {@code message} for a frame that was not refused but still not processed. */
    public static final String NOT_PROCESSED = "Frame not processed";

    /** An ERROR frame carries its words in the {@code message} header and needs no body. */
    private static final byte[] NO_PAYLOAD = new byte[0];

    /**
     * Builds the ERROR frame for a client frame that failed, carrying fixed words and the frame's receipt.
     *
     * @param clientMessage the frame that failed, or null when it could not be decoded at all
     * @param ex            what was thrown while processing it, read for its type and never for its text
     * @return the ERROR frame Spring sends before closing the socket
     */
    @Override
    public Message<byte[]> handleClientMessageProcessingError(@Nullable Message<byte[]> clientMessage, Throwable ex) {
        StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
        error.setMessage(frameMessageFor(ex));
        // As Spring's own handler builds it, which is the frame StompSubProtocolHandler was written to send.
        error.setLeaveMutable(true);
        StompHeaderAccessor client = clientMessage == null
                ? null
                : MessageHeaderAccessor.getAccessor(clientMessage, StompHeaderAccessor.class);
        if (client != null && client.getReceipt() != null) {
            error.setReceiptId(client.getReceipt());
        }
        return handleInternal(error, NO_PAYLOAD, ex, client);
    }

    private static String frameMessageFor(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof StompFrameRefusedException refused) {
                return refused.reason().frameMessage();
            }
        }
        return NOT_PROCESSED;
    }
}
