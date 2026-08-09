package com.chapchapgateway.global.error;

import com.chapchapgateway.global.response.GlobalResponse;
import com.chapchapgateway.global.response.constant.CustomResponseCode;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(-2) // Spring의 기본 ErrorWebExceptionHandler(-1) 보다 먼저 실행시키기 위해 '-2' 설정
@RequiredArgsConstructor
public class GlobalErrorWebExceptionHandler implements WebExceptionHandler {
    private final ObjectMapper objectMapper;

    @Override
    @NonNull
    public Mono<Void> handle(@NonNull ServerWebExchange exchange, @NonNull Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();

        CustomResponseCode customResponseCode = (
            ex instanceof ResponseStatusException res
            && res.getStatusCode().value() == 404)
            ? CustomResponseCode.SCG_NOT_FOUND_ERROR : CustomResponseCode.SCG_SYSTEM_ERROR;

        response.setStatusCode(customResponseCode.getHttpStatus()); // Http Status 변경
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON); // Content-Type을 Json으로 변경

        byte[] bytes = objectMapper.writeValueAsBytes(GlobalResponse.from(customResponseCode));
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }
}
