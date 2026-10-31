package com.healthupgrades.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.common.websocket.StompSessionRegistry;
import com.healthupgrades.support.RepositoryVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The words the server closes a socket with, once its session has ended, are the words the browser looks
 * for (ADR-054).
 *
 * <p>The client ends the session in the tab (ADR-066) on that frame and on no other ERROR, because every
 * other ERROR is about one frame, an outage included ({@code StompRefusalErrorHandler}). So the two copies
 * of the message are a contract: changed on one side only, the tab would go on showing the ended
 * session's data and presenting its dead token, refused and audited every five seconds until it lapsed,
 * and nothing else would notice.
 */
class SessionEndedFrameContractTest {

    /** The browser's side, relative to the repository root. */
    private static final String SOCKET_MODULE = "frontend/src/api/notificationSocket.ts";

    /** The declaration as Prettier writes it: single quotes, one line. */
    private static final Pattern DECLARATION = Pattern.compile("const SESSION_ENDED = '([^']*)';");

    @Test
    void GivenTheFrameTheServerEndsASocketWith_WhenTheBrowserLooksForIt_ThenBothNameTheSameMessage()
            throws IOException {
        String socketModule =
                Files.readString(RepositoryVersion.repositoryRoot().resolve(SOCKET_MODULE));

        Matcher declared = DECLARATION.matcher(socketModule);

        assertThat(declared.find())
                .describedAs("%s must declare the message it looks for as %s", SOCKET_MODULE, DECLARATION.pattern())
                .isTrue();
        assertThat(declared.group(1)).isEqualTo(StompSessionRegistry.SESSION_ENDED);
    }
}
