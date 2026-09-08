package com.chapchapgateway.global.filter;

import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Internal APIs are reachable only through service-to-service connections. */
@Component
public class InternalApiBoundaryFilter implements WebFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Inspect decoded path segments, including matrix-parameter-free values.
        boolean internal = exchange.getRequest().getPath().pathWithinApplication().elements().stream()
                .filter(org.springframework.http.server.PathContainer.PathSegment.class::isInstance)
                .map(org.springframework.http.server.PathContainer.PathSegment.class::cast)
                .anyMatch(segment -> "internal".equalsIgnoreCase(segment.valueToMatch()));
        if (internal) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
