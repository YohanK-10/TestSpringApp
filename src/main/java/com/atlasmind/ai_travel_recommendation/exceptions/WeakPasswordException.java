package com.atlasmind.ai_travel_recommendation.exceptions;

/**
 * Thrown when a user tries to register with a password that doesn't
 * meet the strength requirements.
 *
 * Maps to HTTP 400 BAD_REQUEST.
 *
 * You were previously using ResponseStatusException(HttpStatus.BAD_REQUEST, "...")
 * in AuthService. Same reasoning as DuplicateResourceException — the service
 * layer shouldn't deal with HTTP concepts.
 */
public class WeakPasswordException extends RuntimeException {
    public WeakPasswordException(String message) {
        super(message);
    }
}
