package com.atlasmind.ai_travel_recommendation.controller;

import com.atlasmind.ai_travel_recommendation.dto.EmailOnlyDto;
import com.atlasmind.ai_travel_recommendation.dto.LoginUserDto;
import com.atlasmind.ai_travel_recommendation.dto.RegisterUserDto;
import com.atlasmind.ai_travel_recommendation.dto.VerifyUserDto;
import com.atlasmind.ai_travel_recommendation.dto.response.UserResponseDto;
import com.atlasmind.ai_travel_recommendation.models.RefreshToken;
import com.atlasmind.ai_travel_recommendation.models.User;
import com.atlasmind.ai_travel_recommendation.service.AuthService;
import com.atlasmind.ai_travel_recommendation.service.JwtService;
import com.atlasmind.ai_travel_recommendation.service.RefreshTokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor // Fields with final or with @NonNull!! Beans which are not initialized
public class AuthController {
    private final JwtService jwtService;
    private final AuthService authenticationService;
    private final RefreshTokenService refreshTokenService;
    // ResponseEntity is the HTTP Response Body.
    // Spring deserializes the incoming JSON to the RegisterDto Java object, this is done by @RequestBody.
    @PostMapping("/register")
    public ResponseEntity<UserResponseDto> register(@RequestBody RegisterUserDto registerUserDto) {
        User registerUser = authenticationService.signUp(registerUserDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponseDto.fromUser(registerUser));
    }

    // No. That’s the beauty of using HttpOnly cookies. The browser automatically stores the Set-Cookie header.
    // On every subsequent request (to the same domain), the browser automatically includes the cookie in the Cookie: header.
    // You do not need to store or manually attach the JWT in your frontend code.
    @PostMapping("/login")
    public ResponseEntity<?> authenticate(@RequestBody LoginUserDto loginUserDto) throws NoSuchAlgorithmException, InvalidKeySpecException {
        User loggedUser = authenticationService.authenticate(loginUserDto);
        String jwtToken = jwtService.generateToken(loggedUser);
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(loggedUser);
        // Build cookies for the access and refresh tokens.  The HttpOnly flag
        // prevents JavaScript from accessing them and the Secure flag ensures
        // they are only sent over HTTPS.  We set SameSite=None to allow
        // cross‑site requests from your front‑end domain, but you can use Lax
        // if both front‑end and back‑end share the same domain.
        ResponseCookie responseCookie = ResponseCookie.from("jwt", jwtToken) // static method that starts building the cookie.
                .httpOnly(true)
                .secure(true) // https
                .sameSite("None") // Only send this cookie if the user is actively navigating or making same-site requests.
                // Don’t attach it on malicious hidden POST requests from other sites.
                .path("/") // Makes the cookie available to all endpoints on your site.
                .maxAge(Duration.ofMinutes(30))
                .build();
        ResponseCookie responseCookieToken = ResponseCookie.from("refreshToken", refreshToken.getToken())
                .httpOnly(true)
                .sameSite("None")
                .path("/")
                .maxAge(Duration.ofMillis(refreshTokenService.getRefreshExpirationTimeMs()))
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, responseCookie.toString(), responseCookieToken.toString())
                .body("Login successful!!");
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verifyUser(@RequestBody VerifyUserDto verifyUserDto) {
        authenticationService.verifyUser(verifyUserDto);
        return ResponseEntity.ok("Account is verified!!");
    }

    @PostMapping("/resend")
    public ResponseEntity<?> resendVerify(@RequestBody EmailOnlyDto emailOnlyDto) {
        authenticationService.resendVerificationCode(emailOnlyDto.getEmail());
        return ResponseEntity.ok("Verification code resent successfully!");
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("refreshToken".equals(cookie.getName())) refreshTokenService.revokeToken(refreshTokenService.findByToken(cookie.getValue()));
            }
        }
        ResponseCookie responseCookie = ResponseCookie.from("jwt", "")
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .path("/")
                .maxAge(0)
                .build();
        ResponseCookie responseCookieToken = ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .path("/")
                .maxAge(0)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, responseCookie.toString(), responseCookieToken.toString())
                .body("Logged out successfully!!");
    }

    /**
     * Endpoint used by the client to obtain a new access token using a refresh
     * token. The refresh token is retrieved from the "refreshToken" cookie.  If
     * the token is valid, it is rotated – the old token is revoked and a new
     * refresh token is returned along with a fresh access token.  If invalid or
     * expired, the request will be rejected and the user must log in again.
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request) throws NoSuchAlgorithmException, InvalidKeySpecException {
        String refreshTokenString = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("refreshToken".equals(cookie.getName())) {
                    refreshTokenString = cookie.getValue();
                    break;
                }
            }
        }
        if (refreshTokenString == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Refresh token is missing");
        }

            // Look up the refresh token in the database and verify it hasn’t
            // expired or been revoked.  If invalid, an exception will be thrown.
            RefreshToken existingToken = refreshTokenService.findByToken(refreshTokenString);
            refreshTokenService.verifyIfValidOrNot(existingToken);
            // Rotate the token: mark the old one as revoked and issue a new one.
            RefreshToken newRefreshToken = refreshTokenService.rotateRefreshToken(existingToken);
            User user = newRefreshToken.getUser();
            // Generate a new access token for this user.
            String newAccessToken = jwtService.generateToken(user);
            // Build cookies for the new tokens.
            ResponseCookie jwtCookie = ResponseCookie.from("jwt", newAccessToken)
                    .httpOnly(true)
                    .secure(true)
                    .sameSite("None")
                    .path("/")
                    .maxAge(Duration.ofMillis(jwtService.getExpirationTime()))
                    .build();
            ResponseCookie refreshCookie = ResponseCookie.from("refreshToken", newRefreshToken.getToken())
                    .httpOnly(true)
                    .secure(true)
                    .sameSite("None")
                    .path("/")
                    .maxAge(Duration.ofMillis(refreshTokenService.getRefreshExpirationTimeMs()))
                    .build();
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, jwtCookie.toString(), refreshCookie.toString())
                    .body("Token refreshed");
    }
}
