package com.atlasmind.atlaswatch.controller;

import com.atlasmind.atlaswatch.dto.LoginUserDto;
import com.atlasmind.atlaswatch.dto.PasswordResetConfirmDto;
import com.atlasmind.atlaswatch.dto.RegisterUserDto;
import com.atlasmind.atlaswatch.dto.response.UserResponseDto;
import com.atlasmind.atlaswatch.models.RefreshToken;
import com.atlasmind.atlaswatch.models.User;
import com.atlasmind.atlaswatch.service.AuthService;
import com.atlasmind.atlaswatch.service.JwtService;
import com.atlasmind.atlaswatch.service.RefreshTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private JwtService jwtService;
    @Mock
    private AuthService authService;
    @Mock
    private RefreshTokenService refreshTokenService;

    @InjectMocks
    private AuthController authController;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registerReturnsSafeUserResponseDtoWithoutPasswordField() throws Exception {
        RegisterUserDto dto = new RegisterUserDto("alice@example.com", "StrongPass1!", "alice");
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setPassword("encoded-password");
        when(authService.signUp(dto)).thenReturn(user);

        ResponseEntity<UserResponseDto> response = authController.register(dto);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        String json = objectMapper.writeValueAsString(response.getBody());
        assertTrue(json.contains("alice@example.com"));
        assertFalse(json.contains("password"));
        assertFalse(json.contains("encoded-password"));
    }

    @Test
    void refreshWithoutRefreshTokenCookieReturnsUnauthorized() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(null);

        ResponseEntity<?> response = authController.refresh(request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("Refresh token is missing", response.getBody());
    }

    @Test
    void authenticateOnLocalHttpUsesLaxNonSecureCookies() throws Exception {
        LoginUserDto dto = new LoginUserDto("alice", "StrongPass1!");
        HttpServletRequest request = mock(HttpServletRequest.class);
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setPassword("encoded-password");
        RefreshToken refreshToken = new RefreshToken(
                1L,
                "refresh-token",
                LocalDateTime.now().plusDays(1),
                user,
                false
        );

        when(request.isSecure()).thenReturn(false);
        when(authService.authenticate(dto)).thenReturn(user);
        when(jwtService.generateToken(user)).thenReturn("jwt-token");
        when(refreshTokenService.createRefreshToken(user)).thenReturn(refreshToken);

        ResponseEntity<?> response = authController.authenticate(dto, request);

        String setCookieHeader = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookieHeader);
        assertTrue(setCookieHeader.contains("SameSite=Lax"));
        assertFalse(setCookieHeader.contains("Secure"));
    }

    @Test
    void confirmPasswordResetDelegatesToService() {
        PasswordResetConfirmDto dto = new PasswordResetConfirmDto("alice@example.com", "123456", "StrongPass1!");

        ResponseEntity<?> response = authController.confirmPasswordReset(dto);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Password reset successfully!", response.getBody());
        verify(authService).resetPassword(dto);
    }
}

