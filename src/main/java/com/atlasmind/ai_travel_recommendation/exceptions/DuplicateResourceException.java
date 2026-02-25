package com.atlasmind.ai_travel_recommendation.exceptions;


/**
 * Thrown when a user tries to create a resource that already exists.
 * For example: registering with an email that's already taken.
 *
 * This maps to HTTP 409 CONFLICT — which is the correct status code
 * for "the request conflicts with the current state of the server."
 * You were previously using ResponseStatusException(HttpStatus.CONFLICT, "...")
 * in AuthService. This custom exception is better because:
 * 1. The service layer shouldn't know about HTTP status codes — that's the controller's job
 * 2. A typed exception is catchable and testable (you can write: assertThrows(DuplicateResourceException.class, ...))
 * 3. The GlobalExceptionHandler decides the HTTP status, keeping that logic in one place
 */
public class DuplicateResourceException extends RuntimeException {
    public DuplicateResourceException(String message) {
        super(message);
    }
    public DuplicateResourceException(String resourceName, String fieldName, String fieldValue) {
        super(String.format("%s not found with %s: %s", resourceName, fieldName, fieldValue));
    }
}
