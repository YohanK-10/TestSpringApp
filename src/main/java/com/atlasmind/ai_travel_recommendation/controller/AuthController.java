package com.atlasmind.ai_travel_recommendation.controller;

import com.atlasmind.ai_travel_recommendation.config.AuthCookieFactory;
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
@RequiredArgsConstructor
public class AuthController {
    private final JwtService jwtService;
    private final AuthService authenticationService;
    private final RefreshTokenService refreshTokenService;

    @PostMapping("/register")
    public ResponseEntity<UserResponseDto> register(@RequestBody RegisterUserDto registerUserDto) {
        User registerUser = authenticationService.signUp(registerUserDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponseDto.fromUser(registerUser));
    }

    @PostMapping("/login")
    public ResponseEntity<?> authenticate(
            @RequestBody LoginUserDto loginUserDto,
            HttpServletRequest request
    ) throws NoSuchAlgorithmException, InvalidKeySpecException {
        User loggedUser = authenticationService.authenticate(loginUserDto);
        String jwtToken = jwtService.generateToken(loggedUser);
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(loggedUser);

        ResponseCookie jwtCookie = AuthCookieFactory.buildHttpOnlyCookie(
                request,
                "jwt",
                jwtToken,
                Duration.ofMinutes(30)
        );
        ResponseCookie refreshCookie = AuthCookieFactory.buildHttpOnlyCookie(
                request,
                "refreshToken",
                refreshToken.getToken(),
                Duration.ofMillis(refreshTokenService.getRefreshExpirationTimeMs())
        );

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, jwtCookie.toString(), refreshCookie.toString())
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
                if ("refreshToken".equals(cookie.getName())) {
                    refreshTokenService.revokeToken(refreshTokenService.findByToken(cookie.getValue()));
                }
            }
        }

        ResponseCookie jwtCookie = AuthCookieFactory.clearHttpOnlyCookie(request, "jwt");
        ResponseCookie refreshCookie = AuthCookieFactory.clearHttpOnlyCookie(request, "refreshToken");

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, jwtCookie.toString(), refreshCookie.toString())
                .body("Logged out successfully!!");
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
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

        RefreshToken existingToken = refreshTokenService.findByToken(refreshTokenString);
        refreshTokenService.verifyIfValidOrNot(existingToken);
        RefreshToken newRefreshToken = refreshTokenService.rotateRefreshToken(existingToken);
        User user = newRefreshToken.getUser();
        String newAccessToken = jwtService.generateToken(user);

        ResponseCookie jwtCookie = AuthCookieFactory.buildHttpOnlyCookie(
                request,
                "jwt",
                newAccessToken,
                Duration.ofMillis(jwtService.getExpirationTime())
        );
        ResponseCookie refreshCookie = AuthCookieFactory.buildHttpOnlyCookie(
                request,
                "refreshToken",
                newRefreshToken.getToken(),
                Duration.ofMillis(refreshTokenService.getRefreshExpirationTimeMs())
        );

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, jwtCookie.toString(), refreshCookie.toString())
                .body("Token refreshed");
    }
}
