package com.atlasmind.ai_travel_recommendation.exceptions;

/**
 * Thrown when a requested resource does not exist in the database.
 *
 * This replaces scattered RuntimeException("User not found!!") calls.
 * By having a typed exception, the GlobalExceptionHandler can catch
 * ResourceNotFoundException specifically and return a 404 status code,
 * while letting other exceptions map to different status codes.
 *
 * The three-parameter constructor lets you build descriptive messages:
 *   throw new ResourceNotFoundException("User", "email", "john@gmail.com")
 *   → "User not found with email: john@gmail.com"
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
    public ResourceNotFoundException(String resourceName, String fieldName, String fieldValue) {
        super(String.format("%s not found with %s: %s", resourceName, fieldName, fieldValue));
    }
}
