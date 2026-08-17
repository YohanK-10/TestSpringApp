package com.atlasmind.atlaswatch.util;

public class PasswordValidator {

    // Made static because it doesn't need to be instantiated.
    public static Boolean isStrong(String password) {
        return password != null &&
                password.length() >= 8 &&
                password.matches(".*[A-Z].*") &&       // at least one uppercase
                password.matches(".*[a-z].*") &&       // at least one lowercase
                password.matches(".*\\d.*") &&         // at least one digit
                password.matches(".*[!@#$%^&*()].*");  // at least one special character
    }
}

