package com.chapchapgateway.global.jwt;

import com.chapchapgateway.global.error.custom.InvalidTokenException;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import javax.crypto.SecretKey;
import java.util.Optional;

@Component
public class JwtProvider {
    private final SecretKey secretKey;
    private final JwtConfig jwtConfig;

    public JwtProvider(JwtConfig jwtConfig) {
        this.jwtConfig = jwtConfig;
        this.secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtConfig.secret()));
    }

    public Optional<String> extractAccessToken(ServerWebExchange exchange) {
        String bearerToken = exchange // 모든 요청이 들어있고
                                 .getRequest() // 리퀘스트만 가져오고
                                 .getHeaders() // 헤더를 추출한다
                                 .getFirst(jwtConfig.headerKey());
        if (bearerToken == null || !bearerToken.startsWith(jwtConfig.scheme())) {
            return Optional.empty();
        }
        return Optional.of(bearerToken.substring(jwtConfig.scheme().length()).trim());
    }
    public Claims extractClaims(String token) {
        try{
            return Jwts.parser()
                       .verifyWith(this.secretKey)
                       .build()
                       .parseSignedClaims(token)
                       .getPayload();
        } catch (ExpiredJwtException e) {
            throw new InvalidTokenException("토큰이 만료됐습니다.");
        } catch (UnsupportedJwtException e) {
            throw new InvalidTokenException("서명이 위조된 토큰입니다.");
        } catch (MalformedJwtException e) {
            throw new InvalidTokenException("토큰 형식이 올바르지 않습니다.");
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("토큰 검증에 실패했습니다.");
        }
    }
}
