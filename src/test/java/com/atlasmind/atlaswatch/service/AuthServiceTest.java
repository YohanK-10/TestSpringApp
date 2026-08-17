package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.dto.LoginUserDto;
import com.atlasmind.atlaswatch.dto.PasswordResetConfirmDto;
import com.atlasmind.atlaswatch.dto.RegisterUserDto;
import com.atlasmind.atlaswatch.exceptions.VerificationException;
import com.atlasmind.atlaswatch.exceptions.WeakPasswordException;
import com.atlasmind.atlaswatch.models.User;
import com.atlasmind.atlaswatch.repository.UserRepository;
import com.atlasmind.atlaswatch.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private EmailService emailService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthenticationManager authenticationManager;

    @InjectMocks
    private AuthService authService;

    @Test
    void signUpCreatesDisabledUserAndSendsVerificationEmail() throws Exception {
        RegisterUserDto dto = new RegisterUserDto("user@example.com", "StrongPass1!", "alice");
        when(userRepository.findByEmail(dto.getEmail())).thenReturn(Optional.empty());
        when(userRepository.findByUsername(dto.getUsername())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(dto.getPassword())).thenReturn("encoded-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User result = authService.signUp(dto);

        assertEquals("alice", result.getUsername());
        assertEquals("user@example.com", result.getEmail());
        assertFalse(result.isEnabled());
        assertNotNull(result.getVerificationCode());
        assertNotNull(result.getExpirationTimeOfVerificationCode());
        verify(emailService).sendVerificationEmail(eq("user@example.com"), anyString(), contains(result.getVerificationCode()));
        verify(userRepository).save(result);
    }

    @Test
    void signUpRejectsWeakPassword() {
        RegisterUserDto dto = new RegisterUserDto("user@example.com", "weak", "alice");

        assertThrows(WeakPasswordException.class, () -> authService.signUp(dto));
        verifyNoInteractions(userRepository, emailService, passwordEncoder);
    }

    @Test
    void authenticateRejectsUnverifiedUser() {
        User disabledUser = TestFixtures.user(1L, "alice", "user@example.com");
        disabledUser.setEnable(false);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(disabledUser));

        assertThrows(DisabledException.class,
                () -> authService.authenticate(new LoginUserDto("alice", "StrongPass1!")));

        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void requestPasswordResetStoresCodeAndEmailsUser() throws Exception {
        User user = TestFixtures.user(1L, "alice", "user@example.com");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        authService.requestPasswordReset("user@example.com");

        assertNotNull(user.getPasswordResetCode());
        assertNotNull(user.getExpirationTimeOfPasswordResetCode());
        verify(emailService).sendVerificationEmail(eq("user@example.com"), anyString(), contains(user.getPasswordResetCode()));
        verify(userRepository).save(user);
    }

    @Test
    void resetPasswordRejectsWrongCode() {
        User user = TestFixtures.user(1L, "alice", "user@example.com");
        user.setPasswordResetCode("123456");
        user.setExpirationTimeOfPasswordResetCode(java.time.LocalDateTime.now().plusMinutes(5));
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        assertThrows(VerificationException.class, () -> authService.resetPassword(
                new PasswordResetConfirmDto("user@example.com", "999999", "StrongPass1!")));
    }
}

