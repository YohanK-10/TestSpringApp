package com.atlasmind.ai_travel_recommendation.exceptions;

import com.atlasmind.ai_travel_recommendation.dto.response.ErrorResponse;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.View;

import java.time.LocalDateTime;

/**
 * Hey Spring, if any controller throws an exception — I want to decide how to handle it here.
 */
@RestControllerAdvice
@Builder
@Slf4j
public class GlobalExceptionHandler {
    private final View error;

    // ─── 400 BAD REQUEST ─────────────────────────────────────────────
    // Client sent something wrong (bad input, expired code, weak password)

    @ExceptionHandler(VerificationException.class)
    public ResponseEntity<ErrorResponse> handleVerificationException(VerificationException e) {
        return buildResponse(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(WeakPasswordException.class)
    public ResponseEntity<ErrorResponse> handleWeakPasswordException(WeakPasswordException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    // ─── 401 UNAUTHORIZED ────────────────────────────────────────────
    // Authentication failed (bad password, invalid/expired token)

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> badCredentials(BadCredentialsException ex) {
        return buildResponse(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }
    @ExceptionHandler(RefreshTokenNotFoundException.class)
    public ResponseEntity<?> handleRefreshTokenNotFound(RefreshTokenNotFoundException ex) {
        return buildResponseWithClearedCookies(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(ValidationOfRefreshTokenException.class)
    public ResponseEntity<?> handleInvalidToken(ValidationOfRefreshTokenException ex) {
        return buildResponseWithClearedCookies(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    // ─── 403 FORBIDDEN ───────────────────────────────────────────────
    // User is authenticated but not allowed (e.g., account not verified)

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ErrorResponse> handleDisabledException(DisabledException ex) {
        return buildResponse(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    // ─── 404 NOT FOUND ───────────────────────────────────────────────

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFoundException(ResourceNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(UsernameNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUsernameNotFoundException(UsernameNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    // ─── 409 CONFLICT ────────────────────────────────────────────────

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateResource(DuplicateResourceException ex) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(AccessDeniedException ex) {
        return buildResponse(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    // ─── 500 INTERNAL SERVER ERROR (catch-all) ───────────────────────
    // Something unexpected went wrong on the server.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleExceptions(Exception ex) {
        log.error("Unhandled exception while processing request", ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                             "An unexpected error occurred. Please try again later.");
    }
    private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status, String errorMessage) {
        ErrorResponse response = ErrorResponse.builder()
                .status(status.value())
                .timeStamp(LocalDateTime.now())
                .error(status.getReasonPhrase())
                .message(errorMessage)
                .build();
        return ResponseEntity.status(status).body(response);
    }

    public ResponseEntity<ErrorResponse> buildResponseWithClearedCookies(HttpStatus status, String errorMessage) {
        // If something goes wrong, clear any cookies so that the client
        // doesn’t continue sending an invalid token. The user must re‑authenticate.
        ErrorResponse response = ErrorResponse.builder()
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(errorMessage)
                .timeStamp(LocalDateTime.now())
                .build();
        ResponseCookie clearJwt = ResponseCookie.from("jwt", "")
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .path("/")
                .maxAge(0)
                .build();
        ResponseCookie clearRefresh = ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .path("/")
                .maxAge(0)
                .build();
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, clearJwt.toString(), clearRefresh.toString())
                .body(response);
    }
}
