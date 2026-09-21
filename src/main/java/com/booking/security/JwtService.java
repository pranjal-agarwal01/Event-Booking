package com.booking.security;

import com.booking.user.Role;
import com.booking.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Issues and verifies HS256 access tokens. */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey key;
    private final String issuer;
    private final long accessTokenTtlSeconds;

    public JwtService(@Value("${security.jwt.secret}") String base64Secret,
                      @Value("${security.jwt.issuer}") String issuer,
                      @Value("${security.jwt.access-token-ttl-seconds}") long accessTokenTtlSeconds) {
        this.key = Keys.hmacShaKeyFor(decodeSecret(base64Secret));
        this.issuer = issuer;
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
    }

    /**
     * Accepts standard or URL-safe base64, since generated secrets (Render's
     * generateValue, for one) can come in either form. Fewer than 32 decoded bytes
     * makes hmacShaKeyFor throw at startup - failing fast beats a weak key.
     */
    private static byte[] decodeSecret(String secret) {
        try {
            return Decoders.BASE64.decode(secret);
        } catch (DecodingException ex) {
            return Decoders.BASE64URL.decode(secret);
        }
    }

    public long getAccessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTokenTtlSeconds)))
                .signWith(key)
                .compact();
    }

    /**
     * Verifies signature and expiry, then rebuilds the caller from the claims.
     * Empty means not a usable token - the filter stays quiet and the entry point
     * turns it into a 401.
     */
    public Optional<AppUserPrincipal> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return Optional.of(new AppUserPrincipal(
                    Long.valueOf(claims.getSubject()),
                    claims.get("email", String.class),
                    null,
                    Role.valueOf(claims.get("role", String.class))));
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected JWT: {}", ex.getMessage());
            return Optional.empty();
        }
    }
}
