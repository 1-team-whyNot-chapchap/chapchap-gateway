package com.chapchapgateway.global.filter;

import com.chapchapgateway.global.jwt.JwtConfig;
import com.chapchapgateway.global.jwt.JwtProvider;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;

class ConsultationWebSocketTicketFilterTest {
    private final byte[] key = new byte[64];
    private final JwtConfig config = new JwtConfig(Base64.getEncoder().encodeToString(key), "Authorization", "Bearer");
    private final JwtProvider jwt = new JwtProvider(config);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConsultationWebSocketTicketFilter filter =
            new ConsultationWebSocketTicketFilter(jwt, mapper, "http://localhost:5173");

    private String token() {
        return Jwts.builder().subject("42").claim("role", "CUSTOMER")
                .expiration(Date.from(Instant.now().plusSeconds(120)))
                .signWith(Keys.hmacShaKeyFor(key)).compact();
    }
    private String issue(ConsultationWebSocketTicketFilter target) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(ConsultationWebSocketTicketFilter.TICKET_PATH)
                .header("Origin", "http://localhost:5173").header("Authorization", "Bearer " + token()));
        target.filter(exchange, ignored -> Mono.error(new AssertionError("Ticket must not route"))).block();
        return mapper.readTree(exchange.getResponse().getBodyAsString().block()).get("ticket").asString();
    }
    private MockServerWebExchange socket(String ticket, String origin) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(ConsultationWebSocketTicketFilter.SOCKET_PATH)
                .header("Origin", origin).header("Upgrade", "websocket")
                .header("Sec-WebSocket-Protocol", "v12.stomp, ticket." + ticket)
                .header("X-User-Id", "999").header("Cookie", "private=value"));
    }
    @Test
    void ticketIsConsumedOnceAndOnlyVerifiedIdentityReachesCustomer() {
        String ticket = issue(filter);
        var exchange = socket(ticket, "http://localhost:5173");
        AtomicBoolean called = new AtomicBoolean();
        filter.filter(exchange, sanitized -> new AuthFilter(jwt, config, mapper).filter(sanitized, routed -> {
            called.set(true);
            assertThat(routed.getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo("42");
            assertThat(routed.getRequest().getHeaders().getFirst("X-User-Role")).isEqualTo("CUSTOMER");
            assertThat(routed.getRequest().getHeaders().getFirst("Sec-WebSocket-Protocol")).isEqualTo("v12.stomp");
            assertThat(routed.getRequest().getHeaders().getFirst("Authorization")).isNull();
            assertThat(routed.getRequest().getHeaders().getFirst("Cookie")).isNull();
            return Mono.empty();
        })).block();
        assertThat(called).isTrue();
        var replay = socket(ticket, "http://localhost:5173");
        filter.filter(replay, ignored -> Mono.error(new AssertionError("Replay"))).block();
        assertThat(replay.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test
    void wrongOriginAndMissingAuthenticationFailClosed() {
        for (String origin : new String[]{"https://evil.test", "null"}) {
            var request = socket(issue(filter), origin);
            filter.filter(request, ignored -> Mono.error(new AssertionError("Origin"))).block();
            assertThat(request.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        var request = MockServerWebExchange.from(MockServerHttpRequest.post(ConsultationWebSocketTicketFilter.TICKET_PATH)
                .header("Origin", "http://localhost:5173"));
        filter.filter(request, ignored -> Mono.error(new AssertionError("Missing token"))).block();
        assertThat(request.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test
    void expiredJwtAndReplacedTicketCannotConnect() {
        var future = new ConsultationWebSocketTicketFilter(jwt, mapper, "http://localhost:5173",
                Clock.fixed(Instant.now().plusSeconds(180), ZoneOffset.UTC));
        var request = MockServerWebExchange.from(MockServerHttpRequest.post(ConsultationWebSocketTicketFilter.TICKET_PATH)
                .header("Origin", "http://localhost:5173").header("Authorization", "Bearer " + token()));
        future.filter(request, ignored -> Mono.empty()).block();
        assertThat(request.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String old = issue(filter);
        issue(filter);
        var replaced = socket(old, "http://localhost:5173");
        filter.filter(replaced, ignored -> Mono.error(new AssertionError("Replaced"))).block();
        assertThat(replaced.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ticketExpiresAfterThirtySecondsEvenWhenAccessTokenIsValid() {
        var now = new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        var target = new ConsultationWebSocketTicketFilter(jwt, mapper, "http://localhost:5173", clock);
        String value = issue(target);
        now.set(now.get().plusSeconds(31));
        var expired = socket(value, "http://localhost:5173");
        target.filter(expired, ignored -> Mono.error(new AssertionError("Expired ticket"))).block();
        assertThat(expired.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
