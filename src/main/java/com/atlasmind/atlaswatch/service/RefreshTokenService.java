package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.exceptions.RefreshTokenNotFoundException;
import com.atlasmind.atlaswatch.exceptions.ValidationOfRefreshTokenException;
import com.atlasmind.atlaswatch.models.RefreshToken;
import com.atlasmind.atlaswatch.models.User;
import com.atlasmind.atlaswatch.repository.RefreshTokenRepository;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;


/**
 * Service responsible for creating, validating, rotating and revoking
 * refresh tokens.  Refresh tokens are stored server side to allow
 * revocation.  Rotation means that each time a token is exchanged, a
 * brand-new token is created and the old one is marked as revoked【951089229205939†L501-L519】.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    private final RefreshTokenRepository refreshTokenRepository;

    /**
     * -- GETTER --
     *  Expose the configured refresh expiration duration.  This can be used
     *  externally when building cookies to set the proper lifetime.
     */
    @Getter
    @Value("${security.jwt.refreshExpirationTime}") // Ensure your importing the correct @Value and not lombok.value
    private Long refreshExpirationTimeMs;

    public RefreshToken createRefreshToken(User user) {
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setToken(generateToken());
        refreshToken.setExpiryTime(LocalDateTime.now().plusSeconds(refreshExpirationTimeMs/1000));
        refreshToken.setRevoked(false);
        return refreshTokenRepository.save(refreshToken);
    }

    /**
     * Retrieves a refresh token by its string value.  Throws a runtime
     * exception if the token does not exist.
     */
    public RefreshToken findByToken(String token) {
        return refreshTokenRepository.findByToken(token)
                .orElseThrow(() -> new RefreshTokenNotFoundException("Refresh token not found!! Please login again."));
    }

    /**
     * Validates a refresh token by checking that it is not expired and not
     * revoked.  Returns the same token if valid, otherwise throws.
     */
    public void verifyIfValidOrNot(RefreshToken token) {
        if ((token.isRevoked()) || token.getExpiryTime().isBefore(LocalDateTime.now())) {
            throw new ValidationOfRefreshTokenException("Refresh token is expired! Please login again!!");
        }
    }

    /**
     * Rotates the provided token by marking it as revoked and issuing a new
     * token for the same user.  The new token is persisted and returned.
     */
    public RefreshToken rotateRefreshToken(RefreshToken oldRefreshToken) {
        this.revokeToken(oldRefreshToken);
        return createRefreshToken(oldRefreshToken.getUser());
    }

    /**
     * Marks the provided token as revoked.  This is useful for logout.
     */
    public void revokeToken(RefreshToken token) {
        token.setRevoked(true);
        refreshTokenRepository.save(token);
    }

    /**
     * Revokes a presented token when it still exists. Logout remains idempotent
     * when a browser repeats the request or presents an already-cleared cookie.
     */
    public void revokeIfPresent(String token) {
        refreshTokenRepository.findByToken(token).ifPresent(this::revokeToken);
    }

    /**
     * Generates a refresh token for user.
     * @return newly generated token as a string.
     */
    private String generateToken() {
        byte[] tokenArray = new byte[128];
        new SecureRandom().nextBytes(tokenArray);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenArray);
    }
}

