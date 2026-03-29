package com.atlasmind.ai_travel_recommendation.config;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.http.ResponseCookie;

public final class AuthCookieFactory {

    private AuthCookieFactory() {
    }

    public static ResponseCookie buildHttpOnlyCookie(
            HttpServletRequest request,
            String name,
            String value,
            Duration maxAge
    ) {
        boolean secure = isSecureRequest(request);
        String sameSite = secure ? "None" : "Lax";

        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    public static ResponseCookie clearHttpOnlyCookie(HttpServletRequest request, String name) {
        return buildHttpOnlyCookie(request, name, "", Duration.ZERO);
    }

    private static boolean isSecureRequest(HttpServletRequest request) {
        if (request == null) {
            return false;
        }

        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        if (forwardedProto != null && !forwardedProto.isBlank()) {
            String firstValue = forwardedProto.split(",")[0].trim();
            return "https".equalsIgnoreCase(firstValue);
        }

        return request.isSecure();
    }
}
