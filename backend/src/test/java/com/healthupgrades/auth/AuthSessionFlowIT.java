package com.healthupgrades.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthupgrades.auth.adapter.in.web.RefreshCookies;
import com.healthupgrades.support.PostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The session lifecycle over a real socket: sign in, refresh, replay, sign out.
 *
 * <p>Everything here has been asserted somewhere else in isolation. What cannot be asserted anywhere
 * else is that the pieces line up on the wire — that the cookie the server sets is one a client can
 * send back, that the credential in it is the one the row expects, and that a replayed credential really
 * does take the whole session down including the access token already issued within it. Those are
 * properties of the assembled application, and every one of them has failed before in some codebase
 * because a cookie path or a claim name did not match.
 *
 * <p>The JDK {@link HttpClient} is used with cookie handling left off deliberately: the cookies are
 * read and replayed by hand, so a test can present a credential the browser would already have
 * discarded — which is exactly what a replay is.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "app.auth.session.rotation-grace=0s")
class AuthSessionFlowIT extends PostgresIT {

    private static final String PASSWORD = "correct-horse-battery-staple";

    private static final HttpClient client = HttpClient.newHttpClient();

    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    void GivenANewAccount_WhenItRegisters_ThenTheCredentialArrivesInACookieAndTheTokenInTheBody() throws Exception {
        HttpResponse<String> registered = register();

        assertThat(registered.statusCode()).isEqualTo(201);
        assertThat(body(registered).get("accessToken").asText()).isNotBlank();
        assertThat(setCookie(registered))
                .as("the credential must not be reachable from script, on any deployment")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .contains("Path=/api/auth");
    }

    @Test
    void GivenTheCredentialFromSignIn_WhenItIsRefreshed_ThenANewTokenAndANewCredentialComeBack() throws Exception {
        HttpResponse<String> registered = register();
        String first = credential(registered);

        HttpResponse<String> refreshed = refresh(first);

        assertThat(refreshed.statusCode()).isEqualTo(200);
        assertThat(body(refreshed).get("accessToken").asText()).isNotBlank();
        assertThat(credential(refreshed))
                .as("a credential is good for one exchange and no more")
                .isNotEqualTo(first);
    }

    @Test
    void GivenACredentialAlreadyExchanged_WhenItIsReplayed_ThenTheSessionIsEndedForEverybody() throws Exception {
        // The property the whole rotation scheme exists for. The thief is refused, and so is the honest
        // client - signing the real user out is the cost of the only signal available that a credential
        // has been copied.
        HttpResponse<String> registered = register();
        String stolen = credential(registered);
        String live = credential(refresh(stolen));

        HttpResponse<String> replayed = refresh(stolen);

        assertThat(replayed.statusCode())
                .as("the grace window is zero here, so this is a replay and not two tabs")
                .isEqualTo(401);
        assertThat(refresh(live).statusCode())
                .as("the honest client's own credential stops working too")
                .isEqualTo(401);
    }

    @Test
    void GivenAnAccessTokenIssuedWithinASession_WhenThatSessionEnds_ThenTheTokenStopsWorkingAtOnce() throws Exception {
        // The reason a token carries a sid claim at all. Without it, signing out would be a suggestion
        // until the token expired on its own.
        HttpResponse<String> registered = register();
        String accessToken = body(registered).get("accessToken").asText();
        assertThat(me(accessToken).statusCode()).isEqualTo(200);

        logout(credential(registered));

        assertThat(me(accessToken).statusCode()).isEqualTo(401);
    }

    @Test
    void GivenASignedOutSession_WhenItsCredentialIsPresentedAgain_ThenItIsRefused() throws Exception {
        HttpResponse<String> registered = register();
        String credential = credential(registered);

        HttpResponse<String> signedOut = logout(credential);

        assertThat(signedOut.statusCode()).isEqualTo(204);
        assertThat(setCookie(signedOut)).contains("Max-Age=0");
        assertThat(refresh(credential).statusCode()).isEqualTo(401);
    }

    @Test
    void GivenNoRequestedWithHeader_WhenARefreshIsAttempted_ThenItIsRefusedBeforeAnythingIsRead() throws Exception {
        HttpResponse<String> registered = register();
        String credential = credential(registered);

        HttpResponse<String> refused = send(request("/api/auth/refresh")
                .header("Cookie", "refresh_token=" + credential)
                .POST(HttpRequest.BodyPublishers.noBody()));

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refresh(credential).statusCode())
                .as("the refusal must not have consumed the credential")
                .isEqualTo(200);
    }

    @Test
    void GivenAnotherAccountsSession_WhenOneSignsOut_ThenTheOtherIsUnaffected() throws Exception {
        HttpResponse<String> mine = register();
        HttpResponse<String> theirs = register();

        logout(credential(mine));

        assertThat(refresh(credential(theirs)).statusCode())
                .as("sign-out is per device, and certainly per account")
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------ the wire

    private HttpResponse<String> register() throws Exception {
        String email = "flow-" + UUID.randomUUID() + "@example.com";
        String payload = json.writeValueAsString(java.util.Map.of(
                "name", "Someone", "email", email, "password", PASSWORD));
        return send(request("/api/auth/register")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)));
    }

    private HttpResponse<String> refresh(String credential) throws Exception {
        return send(request("/api/auth/refresh")
                .header("Cookie", "refresh_token=" + credential)
                .header(RefreshCookies.REQUESTED_WITH, "XMLHttpRequest")
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    private HttpResponse<String> logout(String credential) throws Exception {
        return send(request("/api/auth/logout")
                .header("Cookie", "refresh_token=" + credential)
                .header(RefreshCookies.REQUESTED_WITH, "XMLHttpRequest")
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    private HttpResponse<String> me(String accessToken) throws Exception {
        return send(request("/api/auth/me")
                .header("Authorization", "Bearer " + accessToken)
                .GET());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private static HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body());
    }

    private static String setCookie(HttpResponse<String> response) {
        Optional<String> header = response.headers().firstValue("Set-Cookie");
        assertThat(header).as("the response was expected to set the refresh cookie").isPresent();
        return header.orElseThrow();
    }

    /** The credential out of a Set-Cookie header, as a browser would have stored it. */
    private static String credential(HttpResponse<String> response) {
        String cookie = setCookie(response);
        String value = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
        assertThat(value).as("the cookie was expected to carry a credential").isNotBlank();
        return value;
    }
}
