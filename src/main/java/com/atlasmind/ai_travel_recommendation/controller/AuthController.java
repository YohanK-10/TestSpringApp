package com.atlasmind.ai_travel_recommendation.controller;

import com.atlasmind.ai_travel_recommendation.dto.EmailOnlyDto;
import com.atlasmind.ai_travel_recommendation.dto.LoginUserDto;
import com.atlasmind.ai_travel_recommendation.dto.RegisterUserDto;
import com.atlasmind.ai_travel_recommendation.dto.VerifyUserDto;
import com.atlasmind.ai_travel_recommendation.models.User;
import com.atlasmind.ai_travel_recommendation.service.AuthService;
import com.atlasmind.ai_travel_recommendation.service.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
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
    // ResponseEntity is the HTTP Response Body.
    // Spring deserializes the incoming JSON to the RegisterDto Java object, this is done by @RequestBody.
    @PostMapping("/register")
    public ResponseEntity<User> register(@RequestBody RegisterUserDto registerUserDto) {
        User registerUser = authenticationService.signUp(registerUserDto);
        return ResponseEntity.ok(registerUser);
    }

    // No. That’s the beauty of using HttpOnly cookies. The browser automatically stores the Set-Cookie header.
    // On every subsequent request (to the same domain), the browser automatically includes the cookie in the Cookie: header.
    // You do not need to store or manually attach the JWT in your frontend code.
    @PostMapping("/login")
    public ResponseEntity<?> authenticate(@RequestBody LoginUserDto loginUserDto) throws NoSuchAlgorithmException, InvalidKeySpecException {
        try {
            User loggedUser = authenticationService.authenticate(loginUserDto);
            String jwtToken = jwtService.generateToken(loggedUser);
            ResponseCookie responseCookie = ResponseCookie.from("jwt", jwtToken) // static method that starts building the cookie.
                    .httpOnly(true)
                    .secure(true) // https
                    .sameSite("None") // Only send this cookie if the user is actively navigating or making same-site requests.
                                    // Don’t attach it on malicious hidden POST requests from other sites.
                    .path("/") // Makes the cookie available to all endpoints on your site.
                    .maxAge(Duration.ofMinutes(30))
                    .build();
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, responseCookie.toString())
                    .body("Login successful!!");
        } catch (BadCredentialsException | UsernameNotFoundException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid credentials");
        } catch (DisabledException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Account not verified");
        }

    }

    @PostMapping("/verify")
    public ResponseEntity<?> verifyUser(@RequestBody VerifyUserDto verifyUserDto) {
        try {
            authenticationService.verifyUser(verifyUserDto);
            return ResponseEntity.ok("Account is verified!!");
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/resend")
    public ResponseEntity<?> resendVerify(@RequestBody EmailOnlyDto emailOnlyDto) {
        try {
            authenticationService.resendVerificationCode(emailOnlyDto.getEmail());
            return ResponseEntity.ok("Verification code resent successfully!");
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        ResponseCookie responseCookie = ResponseCookie.from("jwt", "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, responseCookie.toString())
                .body("Logged out successfully!!");
    }
}
