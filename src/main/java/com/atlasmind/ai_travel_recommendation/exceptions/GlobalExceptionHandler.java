package com.atlasmind.ai_travel_recommendation.exceptions;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Hey Spring, if any controller throws an exception — I want to decide how to handle it here.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RefreshTokenNotFoundException.class)
    public ResponseEntity<?> handleRefreshTokenNotFound(RefreshTokenNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ex.getMessage());
    }

    @ExceptionHandler(ValidationOfRefreshTokenException.class)
    public ResponseEntity<?> handleInvalidToken(ValidationOfRefreshTokenException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ex.getMessage());
    }

    @ExceptionHandler({Exception.class})
    public ResponseEntity<?> handleRefreshErrors(Exception exception) {
        // If something goes wrong, clear any cookies so that the client
        // doesn’t continue sending an invalid token. The user must re‑authenticate.
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
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, clearJwt.toString(), clearRefresh.toString())
                .body(exception.getMessage());
    }
}
