package com.healthupgrades.common.websocket;

import java.util.Objects;

/**
 * A STOMP frame the server refused, and the fixed words the client is told it with.
 *
 * <p>Thrown from {@link JwtChannelInterceptor#preSend}, which is the only way an interceptor can refuse a
 * frame. Spring wraps it on the way out of the inbound channel, and {@link StompRefusalErrorHandler}
 * finds it in the cause chain and writes {@link Reason#frameMessage()} into the ERROR frame. The
 * exception's own message is the server's detail and never reaches the client.
 *
 * <p>An {@link IllegalArgumentException}, as the interceptor's refusals have always been, because the
 * frame is an argument the server will not take.
 */
class StompFrameRefusedException extends IllegalArgumentException {

    /** Why a frame was refused, which is all a client is told about it. */
    enum Reason {
        /** A CONNECT with no bearer token, or one that does not authenticate. */
        UNAUTHENTICATED("Unauthenticated"),

        /**
         * A frame on a connected socket that the interceptor turns down: a SUBSCRIBE it does not authorise,
         * or any command outside its allow-list, a SEND or a client's MESSAGE among them. One sent before
         * any CONNECT never reaches the interceptor: Spring refuses it first, and it reads as
         * {@code Frame not processed}.
         */
        REFUSED("Refused");

        private final String frameMessage;

        Reason(String frameMessage) {
            this.frameMessage = frameMessage;
        }

        /**
         * The ERROR frame's {@code message} header for this reason.
         *
         * @return the fixed words a client reads, the same for every refusal of this kind
         */
        String frameMessage() {
            return frameMessage;
        }
    }

    private final Reason reason;

    /**
     * Refuses a frame.
     *
     * @param reason what the client is told
     * @param detail what the server knows about the refusal, for its own diagnostics only
     * @throws NullPointerException if {@code reason} is null
     */
    StompFrameRefusedException(Reason reason, String detail) {
        super(detail);
        // Fail at the throw site: a null reason would surface only inside Spring's error handling, as an
        // NPE that leaves the client with no ERROR frame at all.
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Says why the frame was refused.
     *
     * @return the reason, whose {@link Reason#frameMessage()} the client reads
     */
    Reason reason() {
        return reason;
    }
}
