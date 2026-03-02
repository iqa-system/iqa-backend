package org.wlow.iqabackend.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT生成与校验服务.
 */
@Service
@RequiredArgsConstructor
public class JwtService {
    @Value("${jwt.secret-key.access-token}")
    private String secretKey;
    @Value("${jwt.expiration.access-token}")
    private long accessTokenExpiration;

    /**
     * 生成AccessToken.
     */
    public String generateAccessToken(String username, Integer userId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(accessTokenExpiration);
        return Jwts.builder()
                .claim("username", username)
                .claim("userId", userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(accessTokenKey())
                .compact();
    }

    /**
     * 解析并校验AccessToken, 校验失败会抛出异常.
     */
    public Claims parseAndValidateAccessToken(String token) {
        return Jwts.parser()
                .verifyWith(accessTokenKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * AccessToken签名秘钥.
     */
    private SecretKey accessTokenKey() {
        return Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
    }
}

