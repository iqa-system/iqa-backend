package org.wlow.iqabackend.data.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token返回体.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthTokenResponse {
    /**
     * AccessToken.
     */
    private String accessToken;

    /**
     * Token类型.
     */
    private String tokenType;

    /**
     * AccessToken过期时间(秒).
     */
    private long expiresIn;
}

