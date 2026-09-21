package com.booking.auth;

import com.booking.auth.dto.*;
import com.booking.common.ConflictException;
import com.booking.common.UnauthorizedException;
import com.booking.security.JwtService;
import com.booking.user.Role;
import com.booking.user.User;
import com.booking.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final Duration refreshTokenTtl;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtService jwtService,
                       @Value("${security.jwt.refresh-token-ttl-days}") long refreshTokenTtlDays) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.refreshTokenTtl = Duration.ofDays(refreshTokenTtlDays);
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("An account with that email already exists");
        }
        User user = new User(request.email(),
                passwordEncoder.encode(request.password()),
                request.fullName(),
                Role.USER);
        return UserResponse.from(userRepository.save(user));
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        try {
            // Delegates to DaoAuthenticationProvider: loads the user, compares the
            // BCrypt hash, and throws if either step fails.
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        } catch (AuthenticationException ex) {
            // Deliberately vague: do not reveal whether the email exists.
            throw new UnauthorizedException("Invalid email or password");
        }

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        return issueTokens(user);
    }

    /**
     * Rotation: the presented token is revoked and a new one issued. If a stolen
     * token is replayed after the real client has refreshed, it is already dead.
     */
    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        UUID token = parseUuid(request.refreshToken());

        RefreshToken stored = refreshTokenRepository.findByToken(token)
                .orElseThrow(() -> new UnauthorizedException("Refresh token is not valid"));

        if (!stored.isUsable()) {
            throw new UnauthorizedException("Refresh token has expired or been revoked");
        }

        stored.revoke();
        return issueTokens(stored.getUser());
    }

    @Transactional
    public void logout(RefreshRequest request) {
        refreshTokenRepository.findByToken(parseUuid(request.refreshToken()))
                .ifPresent(RefreshToken::revoke);
    }

    private AuthResponse issueTokens(User user) {
        RefreshToken refreshToken =
                refreshTokenRepository.save(new RefreshToken(user, Instant.now().plus(refreshTokenTtl)));

        return AuthResponse.of(jwtService.generateAccessToken(user),
                refreshToken.getToken().toString(),
                jwtService.getAccessTokenTtlSeconds());
    }

    private UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new UnauthorizedException("Refresh token is not valid");
        }
    }
}
