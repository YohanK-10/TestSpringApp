package com.atlasmind.ai_travel_recommendation.exceptions;

/**
 * Thrown when email verification fails — code expired, code incorrect,
 * or user is already verified.
 *
 * This replaces the RuntimeException("The verification code is expired!!")
 * and RuntimeException("Invalid verification code!!") scattered through AuthService.
 *
 * Maps to HTTP 400 BAD_REQUEST because the client sent something wrong
 * (an expired or incorrect code). It's not a server error, it's a client error.
 */
public class VerificationException extends RuntimeException {
    public VerificationException(String message) {
        super(message);
    }
}
