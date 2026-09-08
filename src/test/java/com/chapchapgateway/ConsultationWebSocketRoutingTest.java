package com.chapchapgateway;

import com.chapchapgateway.global.jwt.JwtConfig;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.WebsocketServerSpec;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "file:.env.example", properties = "spring.config.import=")
class ConsultationWebSocketRoutingTest {
    static final AtomicReference<String> identity = new AtomicReference<>();
    static final AtomicReference<String> protocol = new AtomicReference<>();
    static final AtomicReference<String> authorization = new AtomicReference<>();
    static final DisposableServer downstream = HttpServer.create().host("127.0.0.1").port(0)
            .route(routes -> routes.get("/ws/customer/consultations", (request, response) -> {
                identity.set(request.requestHeaders().get("X-User-Id"));
                protocol.set(request.requestHeaders().get("Sec-WebSocket-Protocol"));
                authorization.set(request.requestHeaders().get("Authorization"));
                return response.sendWebsocket((in, out) -> out.sendString(in.receive().asString()),
                        WebsocketServerSpec.builder().protocols("v12.stomp").build());
            })).bindNow();
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("CUSTOMER_SERVICE_WS_URI", () -> "ws://127.0.0.1:" + downstream.port());
        properties.add("CORS_ALLOW_ORIGIN", () -> "http://localhost:5173");
    }
    @AfterAll static void stop() { downstream.disposeNow(); }
    @LocalServerPort int port;
    @Autowired JwtConfig jwt;
    @Autowired ObjectMapper mapper;

    @Test
    void realUpgradeAndFramesPassThroughGatewayWithoutCredentialLeak() throws Exception {
        String token = Jwts.builder().subject("42").claim("role", "CUSTOMER")
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwt.secret()))).compact();
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/customer/consultations/ws-ticket"))
                    .header("Origin", "http://localhost:5173").header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            String ticket = mapper.readTree(response.body()).get("ticket").asString();
            CompletableFuture<String> echoed = new CompletableFuture<>();
            WebSocket socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                    .header("Origin", "http://localhost:5173")
                    .subprotocols("v12.stomp", "ticket." + ticket)
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws/customer/consultations"),
                            new WebSocket.Listener() {
                                @Override public void onOpen(WebSocket ws) { ws.request(1); }
                                @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                                    echoed.complete(data.toString()); ws.request(1); return null;
                                }
                                @Override public void onError(WebSocket ws, Throwable error) {
                                    echoed.completeExceptionally(error);
                                }
                            }).get(10, TimeUnit.SECONDS);
            try {
                socket.sendText("test-frame", true).get(5, TimeUnit.SECONDS);
                assertThat(echoed.get(5, TimeUnit.SECONDS)).isEqualTo("test-frame");
                assertThat(socket.getSubprotocol()).isEqualTo("v12.stomp");
                assertThat(identity.get()).isEqualTo("42");
                assertThat(protocol.get()).isEqualTo("v12.stomp");
                assertThat(authorization.get()).isNull();
            } finally { socket.abort(); }
        }
    }
}
