package com.atlasmind.ai_travel_recommendation.exceptions;

import com.atlasmind.ai_travel_recommendation.config.AuthCookieFactory;
import com.atlasmind.ai_travel_recommendation.dto.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
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

@RestControllerAdvice
@Builder
@Slf4j
public class GlobalExceptionHandler {
    private final View error;

    @ExceptionHandler(VerificationException.class)
    public ResponseEntity<ErrorResponse> handleVerificationException(VerificationException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(WeakPasswordException.class)
    public ResponseEntity<ErrorResponse> handleWeakPasswordException(WeakPasswordException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> badCredentials(BadCredentialsException ex) {
        return buildResponse(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(RefreshTokenNotFoundException.class)
    public ResponseEntity<?> handleRefreshTokenNotFound(
            RefreshTokenNotFoundException ex,
            HttpServletRequest request
    ) {
        return buildResponseWithClearedCookies(HttpStatus.UNAUTHORIZED, ex.getMessage(), request);
    }

    @ExceptionHandler(ValidationOfRefreshTokenException.class)
    public ResponseEntity<?> handleInvalidToken(
            ValidationOfRefreshTokenException ex,
            HttpServletRequest request
    ) {
        return buildResponseWithClearedCookies(HttpStatus.UNAUTHORIZED, ex.getMessage(), request);
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ErrorResponse> handleDisabledException(DisabledException ex) {
        return buildResponse(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFoundException(ResourceNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(UsernameNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUsernameNotFoundException(UsernameNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleExceptions(Exception ex) {
        log.error("Unhandled exception while processing request", ex);
        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again later."
        );
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

    public ResponseEntity<ErrorResponse> buildResponseWithClearedCookies(
            HttpStatus status,
            String errorMessage,
            HttpServletRequest request
    ) {
        ErrorResponse response = ErrorResponse.builder()
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(errorMessage)
                .timeStamp(LocalDateTime.now())
                .build();

        ResponseCookie clearJwt = AuthCookieFactory.clearHttpOnlyCookie(request, "jwt");
        ResponseCookie clearRefresh = AuthCookieFactory.clearHttpOnlyCookie(request, "refreshToken");

        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, clearJwt.toString(), clearRefresh.toString())
                .body(response);
    }
}
