package com.chapchapgateway.global.filter;

import com.chapchapgateway.global.jwt.JwtConfig;
import com.chapchapgateway.global.jwt.JwtProvider;
import com.chapchapgateway.global.response.GlobalResponse;
import com.chapchapgateway.global.response.constant.CustomResponseCode;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * SCG 전역에서 사용될 인증 처리 필터
 *      - 모든 라우팅 요청에 대해서 JWT검증 및 사용자 식별 헤더를 하위 서비스로 전파시키는 기능
 */
@Component
@RequiredArgsConstructor
public class AuthFilter implements GlobalFilter, Ordered {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLE_HEADER = "X-User-Role";

    private final JwtProvider jwtProvider;
    private final JwtConfig jwtConfig;
    private final ObjectMapper objectMapper;


    /**
     * 게이트웨이를 통과하는 모든 요청에서 JWT를 확인하고,
     * 검증에 성공한 사용자 정보만 하위 서비스에 전달한다.
     */
    @Override
    @NonNull
    public Mono<Void> filter(
            @NonNull ServerWebExchange exchange, // 현재 HTTP 요청·응답을 담고 있는 객체
            @NonNull GatewayFilterChain chain    // 다음 Gateway 필터 또는 실제 라우팅으로 요청을 넘기는 객체
    ) {
        try {

            ServerHttpRequest sanitizedRequest = exchange.getRequest()
                    .mutate()
                    .headers(headers -> {
                        headers.remove(USER_ID_HEADER);
                        headers.remove(USER_ROLE_HEADER);
                        headers.remove("X-User-Expires-At");
                    })
                    .build();

            ServerWebExchange sanitizedExchange = exchange.mutate()
                    .request(sanitizedRequest)
                    .build();

            // 1. 클라이언트 요청의 Authorization 헤더에서 Bearer 토큰을 추출한다.
            // 예시: Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
            Optional<String> optionalToken = jwtProvider.extractAccessToken(sanitizedExchange);

            // 2. 토큰이 없는 요청은 인증하지 않고 그대로 다음 단계로 전달한다.
            // 로그인, 회원가입, Swagger 문서처럼 공개 API가 이 경우에 해당한다.
            // 인증이 필요한 API는 하위 서비스의 @PreAuthorize가 최종적으로 막는다.
            if (optionalToken.isEmpty()) {
                return chain.filter(sanitizedExchange);
            }

            // 3. 토큰이 존재하면 서명·만료 여부를 검증하고 JWT의 Payload(Claims)를 가져온다.
            // 검증 실패 시 예외가 발생하며, 아래 catch에서 401 응답으로 처리한다.
            Claims claims = jwtProvider.extractClaims(optionalToken.get());

            if (claims.getExpiration() == null) return unauthorized(exchange);

            // 4. 하위 서비스로 전달할 요청을 새로 만든다.
            // JWT 원본은 하위 서비스에 전달하지 않고 제거한다.
            // 대신 JWT에서 검증한 사용자 ID와 역할만 내부 헤더로 전달한다.
            ServerHttpRequest serverHttpRequest = sanitizedRequest.mutate()
                    .headers(httpHeaders -> httpHeaders.remove(jwtConfig.headerKey()))
                    .header("X-User-Expires-At", Long.toString(claims.getExpiration().toInstant().getEpochSecond()))
                    .header(USER_ID_HEADER, claims.getSubject())              // JWT의 subject: 사용자 ID
                    .header(USER_ROLE_HEADER, claims.get("role", String.class)) // JWT의 role claim: 사용자 역할
                    .build();

            // 5. 변경된 요청을 다음 필터 또는 라우팅 대상 하위 서비스로 전달한다.
            // 하위 서비스의 HeaderAuthenticationFilter는 X-User-Id, X-User-Role을 읽어
            // SecurityContext를 만들고, @PreAuthorize가 이를 기준으로 권한을 판단한다.
            return chain.filter(
                    sanitizedExchange.mutate()
                            .request(serverHttpRequest)
                            .build()
            );

        } catch (Exception e) {
            // 토큰 만료, 위조, 형식 오류 등 JWT 검증 중 발생한 모든 예외는
            // 인증 실패(401 Unauthorized) 응답으로 처리한다.
            return unauthorized(exchange);
        }
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(CustomResponseCode.SCG_INVALID_TOKEN_ERROR.getHttpStatus());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] bytes = objectMapper.writeValueAsBytes(GlobalResponse.from(CustomResponseCode.SCG_INVALID_TOKEN_ERROR));
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    /**
     * 필터의 실행 순서 결정
     *      - Gateway의 기본 라우팅(0)보다 먼저 실행되어야 하므로 -1을 설정
     * @return
     */
    @Override
    public int getOrder() {
        return -1;
    }
}
