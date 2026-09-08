package com.chapchapgateway.global.filter;

import com.chapchapgateway.global.jwt.JwtProvider;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Single-process development tickets. Never stores or logs credentials outside memory. */
@Component
public class ConsultationWebSocketTicketFilter implements WebFilter, Ordered {
    static final String TICKET_PATH = "/api/customer/consultations/ws-ticket";
    static final String SOCKET_PATH = "/ws/customer/consultations";
    private final JwtProvider jwtProvider;
    private final ObjectMapper mapper;
    private final Set<String> origins;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Ticket> tickets = new HashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ConsultationWebSocketTicketFilter(JwtProvider jwtProvider, ObjectMapper mapper,
            @Value("${CORS_ALLOW_ORIGIN:http://localhost:5173}") String origins) {
        this(jwtProvider, mapper, origins, Clock.systemUTC());
    }

    ConsultationWebSocketTicketFilter(JwtProvider jwtProvider, ObjectMapper mapper,
            String origins, Clock clock) {
        this.jwtProvider = jwtProvider;
        this.mapper = mapper;
        this.clock = clock;
        this.origins = Arrays.stream(origins.split(",")).map(String::trim)
                .filter(value -> !value.isBlank() && !value.equals("*")).collect(Collectors.toSet());
    }

    @Override
    public int getOrder() { return -100; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!TICKET_PATH.equals(path) && !SOCKET_PATH.equals(path)) {
            return chain.filter(exchange);
        }
        List<String> originHeaders = exchange.getRequest().getHeaders().get("Origin");
        if (originHeaders == null || originHeaders.size() != 1 || !origins.contains(originHeaders.getFirst())) {
            return reject(exchange, HttpStatus.FORBIDDEN);
        }
        String origin = originHeaders.getFirst();
        exchange.getResponse().getHeaders().set("Cache-Control", "no-store");
        if (TICKET_PATH.equals(path)) {
            exchange.getResponse().getHeaders().set("Access-Control-Allow-Origin", origin);
            exchange.getResponse().getHeaders().set("Vary", "Origin");
            if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
                exchange.getResponse().getHeaders().set("Access-Control-Allow-Methods", "POST");
                exchange.getResponse().getHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
                return exchange.getResponse().setComplete();
            }
            if (exchange.getRequest().getMethod() != HttpMethod.POST) {
                return reject(exchange, HttpStatus.METHOD_NOT_ALLOWED);
            }
            try {
                String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
                if (authorization == null || !authorization.startsWith("Bearer ")) {
                    return reject(exchange, HttpStatus.UNAUTHORIZED);
                }
                String token = authorization.substring(7);
                Claims claims = validClaims(token);
                long expires = Math.min(clock.millis() + 30_000, claims.getExpiration().getTime());
                String ticket = issue(token, claims.getSubject(), origin, expires);
                if (ticket == null) {
                    return reject(exchange, HttpStatus.TOO_MANY_REQUESTS);
                }
                exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                byte[] body = mapper.writeValueAsBytes(Map.of(
                        "ticket", ticket, "expiresIn", Math.max(0, (expires - clock.millis()) / 1000)));
                return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
            } catch (RuntimeException exception) {
                return reject(exchange, HttpStatus.UNAUTHORIZED);
            }
        }
        if (exchange.getRequest().getMethod() != HttpMethod.GET
                || !"websocket".equalsIgnoreCase(exchange.getRequest().getHeaders().getUpgrade())
                || exchange.getRequest().getURI().getRawQuery() != null) {
            return reject(exchange, HttpStatus.BAD_REQUEST);
        }
        List<String> protocols = exchange.getRequest().getHeaders()
                .getOrEmpty("Sec-WebSocket-Protocol").stream()
                .flatMap(value -> Arrays.stream(value.split(","))).map(String::trim).toList();
        if (protocols.size() != 2 || !protocols.contains("v12.stomp")) {
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }
        String credential = protocols.stream().filter(value -> value.startsWith("ticket."))
                .findFirst().orElse("");
        if (!credential.matches("ticket\\.[A-Za-z0-9_-]{43}")) {
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }
        Ticket ticket = consume(credential.substring(7));
        if (ticket == null || ticket.expires <= clock.millis() || !origin.equals(ticket.origin)) {
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }
        try {
            validClaims(ticket.token);
        } catch (RuntimeException exception) {
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }
        // AuthFilter revalidates the JWT and replaces external identity headers.
        var request = exchange.getRequest().mutate().headers(headers -> {
            headers.remove("Cookie");
            headers.remove("X-User-Id");
            headers.remove("X-User-Role");
            headers.set("Sec-WebSocket-Protocol", "v12.stomp");
            headers.set("Authorization", "Bearer " + ticket.token);
        }).build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    private Claims validClaims(String token) {
        Claims claims = jwtProvider.extractClaims(token);
        long id = Long.parseLong(claims.getSubject());
        if (id <= 0 || claims.getExpiration() == null || claims.getExpiration().getTime() <= clock.millis()
                || !Set.of("CUSTOMER", "RIDER", "ADMIN", "SUPER_ADMIN").contains(claims.get("role", String.class))) {
            throw new IllegalArgumentException("Invalid consultation identity");
        }
        return claims;
    }

    private synchronized String issue(String token, String subject, String origin, long expires) {
        tickets.entrySet().removeIf(entry -> entry.getValue().expires <= clock.millis()
                || entry.getValue().subject.equals(subject));
        if (tickets.size() >= 10_000) { return null; }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(value, new Ticket(token, subject, origin, expires));
        return value;
    }

    private synchronized Ticket consume(String value) { return tickets.remove(value); }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().set("Cache-Control", "no-store");
        return exchange.getResponse().setComplete();
    }

    private static final class Ticket {
        private final String token;
        private final String subject;
        private final String origin;
        private final long expires;
        private Ticket(String token, String subject, String origin, long expires) {
            this.token = token; this.subject = subject; this.origin = origin; this.expires = expires;
        }
    }
}
