package com.healthupgrades.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.auth.adapter.in.web.RefreshCookies;
import com.healthupgrades.auth.domain.model.RefreshToken;
import com.healthupgrades.common.security.JwtTokenProvider;
import com.healthupgrades.support.PostgresIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

    @Autowired
    private JwtTokenProvider tokens;

    @Test
    void GivenANewAccount_WhenItRegisters_ThenTheCredentialArrivesInACookieAndTheTokenInTheBody() throws Exception {
        HttpResponse<String> registered = register();

        assertThat(registered.statusCode()).isEqualTo(201);
        assertThat(body(registered).get("accessToken").asString()).isNotBlank();
        assertThat(setCookie(registered))
                .as("the credential must not be reachable from script, on any deployment")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .contains("Path=/api/auth");
    }

    @Test
    void GivenTheShippedConfiguration_WhenAnAccountRegisters_ThenTheCredentialCookieIsTlsOnly() throws Exception {
        // Asserted here, against the configuration the application actually starts with, because the
        // web slices build their cookie by hand and so cannot see a default. A configurable default of
        // false once shipped the credential in clear text to any deployment that forgot to override it
        // (#87, ADR-041).
        HttpResponse<String> registered = register();

        assertThat(setCookie(registered))
                .as("the credential must never travel over plain HTTP")
                .contains("Secure");
    }

    @Test
    void GivenTheCredentialFromSignIn_WhenItIsRefreshed_ThenANewTokenAndANewCredentialComeBack() throws Exception {
        HttpResponse<String> registered = register();
        String first = credential(registered);

        HttpResponse<String> refreshed = refresh(first);

        assertThat(refreshed.statusCode()).isEqualTo(200);
        assertThat(body(refreshed).get("accessToken").asString()).isNotBlank();
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
        String accessToken = body(registered).get("accessToken").asString();
        assertThat(me(accessToken).statusCode()).isEqualTo(200);

        logout(credential(registered));

        assertThat(me(accessToken).statusCode()).isEqualTo(401);
    }

    @Test
    void GivenATokenNamingOneAccountWithinAnothersSession_WhenItIsPresented_ThenItIsRefused() throws Exception {
        // Minting with the application's own provider stands in for a leaked signing key: the one
        // thing that can produce this pair, since the server never issues it. A session id is no
        // secret and an account id appears in audit entries and log lines (NFR-6), so the key alone
        // must not be enough to borrow one account's live session as another (#286).
        UUID mine = accountId(register());
        HttpResponse<String> theirs = register();
        UUID theirSession = RefreshToken.parse(credential(theirs)).orElseThrow().sessionId();

        assertThat(me(tokens.issue(accountId(theirs), theirSession).value()).statusCode())
                .as("a token minted this way for the session's own account is accepted, so the mint is sound")
                .isEqualTo(200);
        assertThat(me(tokens.issue(mine, theirSession).value()).statusCode())
                .as("the session is live, but it is not the session of the account the token names")
                .isEqualTo(401);
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
                .header("Cookie", "__Secure-refresh_token=" + credential)
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
        String payload =
                json.writeValueAsString(java.util.Map.of("name", "Someone", "email", email, "password", PASSWORD));
        return send(request("/api/auth/register")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)));
    }

    private HttpResponse<String> refresh(String credential) throws Exception {
        return send(request("/api/auth/refresh")
                .header("Cookie", "__Secure-refresh_token=" + credential)
                .header(RefreshCookies.REQUESTED_WITH, "XMLHttpRequest")
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    private HttpResponse<String> logout(String credential) throws Exception {
        return send(request("/api/auth/logout")
                .header("Cookie", "__Secure-refresh_token=" + credential)
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
        assertThat(header)
                .as("the response was expected to set the refresh cookie")
                .isPresent();
        return header.orElseThrow();
    }

    private UUID accountId(HttpResponse<String> signedIn) throws Exception {
        return UUID.fromString(body(signedIn).get("user").get("id").asString());
    }

    /** The credential out of a Set-Cookie header, as a browser would have stored it. */
    private static String credential(HttpResponse<String> response) {
        String cookie = setCookie(response);
        String value = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
        assertThat(value).as("the cookie was expected to carry a credential").isNotBlank();
        return value;
    }
}
