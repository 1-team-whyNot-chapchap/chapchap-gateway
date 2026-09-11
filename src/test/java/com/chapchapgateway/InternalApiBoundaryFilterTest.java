package com.chapchapgateway;

import com.chapchapgateway.global.filter.InternalApiBoundaryFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class InternalApiBoundaryFilterTest {
    @Test
    void internalPathsNeverReachRouting() {
        for (String path : new String[]{"/internal/deliveries/current",
                "/api/subscription/internal/v1/current-state/payment",
                "/api/subscription/%69nternal/v1/current-state/refund",
                "/api/subscription/internal;x=1/v1/current-state/subscription"}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(
                    org.springframework.http.HttpMethod.GET, java.net.URI.create(path)));
            AtomicBoolean called = new AtomicBoolean();
            new InternalApiBoundaryFilter().filter(exchange, ignored -> {
                called.set(true);
                return Mono.empty();
            }).block();
            assertThat(called.get()).as(path).isFalse();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void publicAndDocumentationPathsContinue() {
        for (String path : new String[]{"/api/auth/login", "/api/customer/faqs",
                "/api/subscription/plans", "/api/delivery/current", "/auth-service/api-docs", "/docs"}) {
            AtomicBoolean called = new AtomicBoolean();
            new InternalApiBoundaryFilter().filter(
                    MockServerWebExchange.from(MockServerHttpRequest.get(path)), ignored -> {
                        called.set(true);
                        return Mono.empty();
                    }).block();
            assertThat(called.get()).as(path).isTrue();
        }
    }
}
